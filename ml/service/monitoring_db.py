import json
import logging
import os
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger("MonitoringDB")

DEFAULT_DB_PATH = Path(__file__).resolve().parent.parent / "data" / "monitoring.db"

class MonitoringDB:
    """
    Thread-safe embedded SQLite persistence layer with WAL mode for
    prediction observability, delayed outcome matching, drift metrics, and governance.
    """

    def __init__(self, db_path: Optional[Path] = None):
        env_path = os.getenv("MONITORING_DB_PATH")
        self.db_path = Path(env_path) if env_path else (db_path or DEFAULT_DB_PATH)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.init_db()

    def _get_connection(self) -> sqlite3.Connection:
        conn = sqlite3.connect(str(self.db_path), timeout=10.0)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL;")
        conn.execute("PRAGMA busy_timeout=5000;")
        conn.execute("PRAGMA synchronous=NORMAL;")
        return conn

    def init_db(self):
        with self._get_connection() as conn:
            # 1. Prediction Records Table
            conn.execute("""
            CREATE TABLE IF NOT EXISTS prediction_records (
                prediction_id TEXT PRIMARY KEY,
                predicted_at_utc TEXT NOT NULL,
                model_version TEXT NOT NULL,
                schema_version TEXT NOT NULL,
                flight_number TEXT NOT NULL,
                carrier_code TEXT NOT NULL,
                origin_airport TEXT NOT NULL,
                dest_airport TEXT NOT NULL,
                scheduled_dep_time TEXT,
                scheduled_arr_time TEXT,
                departure_date TEXT,
                status TEXT NOT NULL,
                status_reason TEXT,
                cancellation_probability REAL,
                delay_120m_probability REAL,
                expected_delay_minutes REAL,
                disruption_risk_level TEXT,
                inference_latency_ms REAL NOT NULL,
                is_duration_imputed INTEGER NOT NULL DEFAULT 0,
                is_distance_imputed INTEGER NOT NULL DEFAULT 0,
                imputed_features_count INTEGER NOT NULL DEFAULT 0,
                distance_miles REAL,
                scheduled_elapsed_minutes REAL,
                dep_hour INTEGER,
                arr_hour INTEGER,
                day_of_week INTEGER,
                month INTEGER,
                outcome_status TEXT NOT NULL DEFAULT 'PENDING_OUTCOME',
                matched_outcome_id TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            );
            """)

            # 2. Ground Truth Flight Outcomes Table
            conn.execute("""
            CREATE TABLE IF NOT EXISTS ground_truth_outcomes (
                outcome_id TEXT PRIMARY KEY,
                flight_number TEXT NOT NULL,
                carrier_code TEXT NOT NULL,
                origin_airport TEXT NOT NULL,
                dest_airport TEXT NOT NULL,
                flight_date TEXT NOT NULL,
                actual_dep_time TEXT,
                actual_arr_time TEXT,
                arr_delay_minutes REAL,
                is_cancelled INTEGER NOT NULL,
                is_delayed_120m INTEGER NOT NULL,
                provenance TEXT NOT NULL,
                ingested_at_utc TEXT NOT NULL
            );
            """)

            # Unique index to prevent duplicate outcome ingestion
            conn.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_outcomes_unique_flight 
            ON ground_truth_outcomes (carrier_code, flight_number, origin_airport, dest_airport, flight_date);
            """)

            # Indexes on prediction records
            conn.execute("CREATE INDEX IF NOT EXISTS idx_pred_status ON prediction_records (status);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_pred_date ON prediction_records (departure_date);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_pred_match ON prediction_records (carrier_code, flight_number, origin_airport, dest_airport, departure_date);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_pred_outcome_status ON prediction_records (outcome_status);")

            # 3. Model Alerts Table
            conn.execute("""
            CREATE TABLE IF NOT EXISTS model_alerts (
                alert_id TEXT PRIMARY KEY,
                created_at_utc TEXT NOT NULL,
                category TEXT NOT NULL,
                severity TEXT NOT NULL,
                metric_name TEXT NOT NULL,
                metric_value REAL,
                threshold_value REAL,
                message TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1
            );
            """)

            # 4. Model Governance Registry Table
            conn.execute("""
            CREATE TABLE IF NOT EXISTS model_governance (
                version_id TEXT PRIMARY KEY,
                created_at_utc TEXT NOT NULL,
                artifact_checksums TEXT NOT NULL,
                training_period TEXT NOT NULL,
                validation_period TEXT NOT NULL,
                test_period TEXT NOT NULL,
                schema_version TEXT NOT NULL,
                baseline_metrics TEXT NOT NULL,
                supported_geography TEXT NOT NULL,
                supported_carriers TEXT NOT NULL,
                deployment_status TEXT NOT NULL,
                approved_by TEXT,
                approval_notes TEXT
            );
            """)
            conn.commit()

    def record_prediction(
        self,
        model_version: str,
        schema_version: str,
        flight_number: str,
        carrier_code: str,
        origin_airport: str,
        dest_airport: str,
        scheduled_dep_time: str,
        scheduled_arr_time: Optional[str],
        departure_date: Optional[str],
        status: str,
        status_reason: Optional[str],
        cancellation_probability: Optional[float],
        delay_120m_probability: Optional[float],
        expected_delay_minutes: Optional[float],
        disruption_risk_level: str,
        inference_latency_ms: float,
        is_duration_imputed: bool,
        is_distance_imputed: bool,
        distance_miles: Optional[float],
        scheduled_elapsed_minutes: Optional[float],
        dep_hour: int,
        arr_hour: int,
        day_of_week: int,
        month: int,
    ) -> str:
        pred_id = str(uuid.uuid4())
        now_utc = datetime.now(timezone.utc).isoformat()
        imputed_count = int(is_duration_imputed) + int(is_distance_imputed)

        with self._get_connection() as conn:
            conn.execute("""
            INSERT INTO prediction_records (
                prediction_id, predicted_at_utc, model_version, schema_version,
                flight_number, carrier_code, origin_airport, dest_airport,
                scheduled_dep_time, scheduled_arr_time, departure_date,
                status, status_reason, cancellation_probability, delay_120m_probability,
                expected_delay_minutes, disruption_risk_level, inference_latency_ms,
                is_duration_imputed, is_distance_imputed, imputed_features_count,
                distance_miles, scheduled_elapsed_minutes, dep_hour, arr_hour,
                day_of_week, month, outcome_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                pred_id, now_utc, model_version, schema_version,
                flight_number, carrier_code, origin_airport, dest_airport,
                scheduled_dep_time, scheduled_arr_time, departure_date,
                status, status_reason, cancellation_probability, delay_120m_probability,
                expected_delay_minutes, disruption_risk_level, inference_latency_ms,
                1 if is_duration_imputed else 0, 1 if is_distance_imputed else 0, imputed_count,
                distance_miles, scheduled_elapsed_minutes, dep_hour, arr_hour,
                day_of_week, month, "PENDING_OUTCOME"
            ))

            # Attempt retroactive match if ground truth outcome was already ingested
            if departure_date:
                match = conn.execute("""
                SELECT outcome_id FROM ground_truth_outcomes
                WHERE carrier_code = ? AND flight_number = ? AND origin_airport = ? AND dest_airport = ? AND flight_date = ?
                """, (carrier_code, flight_number, origin_airport, dest_airport, departure_date)).fetchone()
                if match:
                    conn.execute("""
                    UPDATE prediction_records SET outcome_status = 'OUTCOME_AVAILABLE', matched_outcome_id = ?
                    WHERE prediction_id = ?
                    """, (match["outcome_id"], pred_id))

            conn.commit()
        return pred_id

    def ingest_ground_truth(
        self,
        flight_number: str,
        carrier_code: str,
        origin_airport: str,
        dest_airport: str,
        flight_date: str,
        is_cancelled: int,
        is_delayed_120m: int,
        arr_delay_minutes: float,
        provenance: str = "BTS_ON_TIME_DATABASE",
        actual_dep_time: Optional[str] = None,
        actual_arr_time: Optional[str] = None,
    ) -> Tuple[bool, str]:
        """
        Ingests actual outcome for a flight with robust deduplication.
        Matches pending prediction records and marks them OUTCOME_AVAILABLE.
        """
        outcome_id = str(uuid.uuid4())
        now_utc = datetime.now(timezone.utc).isoformat()

        with self._get_connection() as conn:
            try:
                conn.execute("""
                INSERT INTO ground_truth_outcomes (
                    outcome_id, flight_number, carrier_code, origin_airport, dest_airport,
                    flight_date, actual_dep_time, actual_arr_time, arr_delay_minutes,
                    is_cancelled, is_delayed_120m, provenance, ingested_at_utc
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (
                    outcome_id, flight_number, carrier_code, origin_airport, dest_airport,
                    flight_date, actual_dep_time, actual_arr_time, arr_delay_minutes,
                    is_cancelled, is_delayed_120m, provenance, now_utc
                ))
            except sqlite3.IntegrityError:
                logger.info(f"Duplicate outcome ignored for {carrier_code}{flight_number} on {flight_date}")
                existing = conn.execute("""
                SELECT outcome_id FROM ground_truth_outcomes
                WHERE carrier_code = ? AND flight_number = ? AND origin_airport = ? AND dest_airport = ? AND flight_date = ?
                """, (carrier_code, flight_number, origin_airport, dest_airport, flight_date)).fetchone()
                return False, existing["outcome_id"] if existing else "DUPLICATE"

            # Match pending prediction records
            updated = conn.execute("""
            UPDATE prediction_records
            SET outcome_status = 'OUTCOME_AVAILABLE', matched_outcome_id = ?
            WHERE carrier_code = ? AND flight_number = ? AND origin_airport = ? AND dest_airport = ? AND departure_date = ?
              AND outcome_status = 'PENDING_OUTCOME'
            """, (outcome_id, carrier_code, flight_number, origin_airport, dest_airport, flight_date)).rowcount

            logger.info(f"Ingested outcome {outcome_id}: matched {updated} pending prediction records.")
            conn.commit()
            return True, outcome_id

    def get_predictions(
        self,
        model_version: Optional[str] = None,
        status: Optional[str] = None,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        limit: int = 100,
        offset: int = 0
    ) -> List[Dict[str, Any]]:
        query = "SELECT * FROM prediction_records WHERE 1=1"
        params: List[Any] = []
        if model_version:
            query += " AND model_version = ?"
            params.append(model_version)
        if status:
            query += " AND status = ?"
            params.append(status)
        if start_date:
            query += " AND predicted_at_utc >= ?"
            params.append(start_date)
        if end_date:
            query += " AND predicted_at_utc <= ?"
            params.append(end_date)
        query += " ORDER BY predicted_at_utc DESC LIMIT ? OFFSET ?"
        params.extend([limit, offset])

        with self._get_connection() as conn:
            rows = conn.execute(query, params).fetchall()
            return [dict(row) for row in rows]

    def get_prediction_summary(
        self,
        model_version: Optional[str] = None,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None
    ) -> Dict[str, Any]:
        query = "SELECT * FROM prediction_records WHERE 1=1"
        params: List[Any] = []
        if model_version:
            query += " AND model_version = ?"
            params.append(model_version)
        if start_date:
            query += " AND predicted_at_utc >= ?"
            params.append(start_date)
        if end_date:
            query += " AND predicted_at_utc <= ?"
            params.append(end_date)

        with self._get_connection() as conn:
            rows = conn.execute(query, params).fetchall()

        total = len(rows)
        if total == 0:
            return {
                "total_predictions": 0,
                "status_breakdown": {},
                "latency_ms": {"p50": 0.0, "p90": 0.0, "p95": 0.0, "mean": 0.0},
                "imputation_stats": {"duration_imputed_count": 0, "distance_imputed_count": 0, "imputation_rate": 0.0},
                "outcome_status_breakdown": {},
                "carrier_breakdown": {}
            }

        status_counts: Dict[str, int] = {}
        outcome_status_counts: Dict[str, int] = {}
        carrier_counts: Dict[str, int] = {}
        latencies: List[float] = []
        dur_imputed = 0
        dist_imputed = 0

        for r in rows:
            st = r["status"]
            status_counts[st] = status_counts.get(st, 0) + 1
            ost = r["outcome_status"]
            outcome_status_counts[ost] = outcome_status_counts.get(ost, 0) + 1
            cc = r["carrier_code"]
            carrier_counts[cc] = carrier_counts.get(cc, 0) + 1
            latencies.append(float(r["inference_latency_ms"]))
            if r["is_duration_imputed"]:
                dur_imputed += 1
            if r["is_distance_imputed"]:
                dist_imputed += 1

        import numpy as np
        lat_arr = np.array(latencies)
        p50 = float(np.percentile(lat_arr, 50))
        p90 = float(np.percentile(lat_arr, 90))
        p95 = float(np.percentile(lat_arr, 95))
        mean_lat = float(np.mean(lat_arr))
        imputation_rate = float((dur_imputed + dist_imputed) / (total * 2))

        return {
            "total_predictions": total,
            "status_breakdown": status_counts,
            "latency_ms": {
                "p50": round(p50, 2),
                "p90": round(p90, 2),
                "p95": round(p95, 2),
                "mean": round(mean_lat, 2)
            },
            "imputation_stats": {
                "duration_imputed_count": dur_imputed,
                "distance_imputed_count": dist_imputed,
                "imputation_rate": round(imputation_rate, 4)
            },
            "outcome_status_breakdown": outcome_status_counts,
            "carrier_breakdown": carrier_counts
        }

    def get_matched_evaluated_records(
        self,
        model_version: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """
        Retrieves joined prediction records and ground truth outcomes for performance scoring.
        """
        query = """
        SELECT 
            p.prediction_id, p.model_version, p.carrier_code, p.origin_airport, p.dest_airport,
            p.cancellation_probability, p.delay_120m_probability, p.expected_delay_minutes,
            o.is_cancelled AS actual_cancelled,
            o.is_delayed_120m AS actual_delayed_120m,
            o.arr_delay_minutes AS actual_arr_delay,
            o.provenance, o.ingested_at_utc
        FROM prediction_records p
        INNER JOIN ground_truth_outcomes o ON p.matched_outcome_id = o.outcome_id
        WHERE p.status = 'PREDICTION_AVAILABLE'
          AND p.cancellation_probability IS NOT NULL
        """
        params: List[Any] = []
        if model_version:
            query += " AND p.model_version = ?"
            params.append(model_version)

        with self._get_connection() as conn:
            rows = conn.execute(query, params).fetchall()
            return [dict(r) for r in rows]

    def record_alert(
        self,
        category: str,
        severity: str,
        metric_name: str,
        metric_value: float,
        threshold_value: float,
        message: str
    ) -> str:
        alert_id = str(uuid.uuid4())
        now_utc = datetime.now(timezone.utc).isoformat()
        with self._get_connection() as conn:
            conn.execute("""
            INSERT INTO model_alerts (
                alert_id, created_at_utc, category, severity, metric_name,
                metric_value, threshold_value, message, is_active
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
            """, (alert_id, now_utc, category, severity, metric_name, metric_value, threshold_value, message))
            conn.commit()
        return alert_id

    def get_active_alerts(self, limit: int = 50) -> List[Dict[str, Any]]:
        with self._get_connection() as conn:
            rows = conn.execute("""
            SELECT * FROM model_alerts WHERE is_active = 1 ORDER BY created_at_utc DESC LIMIT ?
            """, (limit,)).fetchall()
            return [dict(r) for r in rows]

    def clear_active_alerts(self):
        with self._get_connection() as conn:
            conn.execute("UPDATE model_alerts SET is_active = 0 WHERE is_active = 1;")
            conn.commit()

    def record_governance(
        self,
        version_id: str,
        artifact_checksums: Dict[str, str],
        training_period: str,
        validation_period: str,
        test_period: str,
        schema_version: str,
        baseline_metrics: Dict[str, Any],
        supported_geography: str,
        supported_carriers: str,
        deployment_status: str,
        approved_by: Optional[str] = None,
        approval_notes: Optional[str] = None
    ):
        now_utc = datetime.now(timezone.utc).isoformat()
        with self._get_connection() as conn:
            conn.execute("""
            INSERT OR REPLACE INTO model_governance (
                version_id, created_at_utc, artifact_checksums, training_period,
                validation_period, test_period, schema_version, baseline_metrics,
                supported_geography, supported_carriers, deployment_status, approved_by, approval_notes
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                version_id, now_utc, json.dumps(artifact_checksums),
                training_period, validation_period, test_period,
                schema_version, json.dumps(baseline_metrics),
                supported_geography, supported_carriers, deployment_status,
                approved_by, approval_notes
            ))
            conn.commit()

    def get_governance_records(self) -> List[Dict[str, Any]]:
        with self._get_connection() as conn:
            rows = conn.execute("SELECT * FROM model_governance ORDER BY created_at_utc DESC").fetchall()
            out = []
            for r in rows:
                d = dict(r)
                d["artifact_checksums"] = json.loads(d["artifact_checksums"])
                d["baseline_metrics"] = json.loads(d["baseline_metrics"])
                out.append(d)
            return out
