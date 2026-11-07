import hashlib
import json
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional

from ml.service.config import ARTIFACTS_DIR, MODEL_VERSION
from ml.service.monitoring_db import MonitoringDB

logger = logging.getLogger("ModelGovernance")

class ModelGovernance:
    """
    Maintains versioned model registry records, SHA-256 artifact checksums,
    provenance metadata, and safe rollback verifications.
    """

    def __init__(self, db: Optional[MonitoringDB] = None):
        self.db = db or MonitoringDB()
        self.initialize_v2_governance()

    @staticmethod
    def compute_checksums(directory: Path, pattern: str = "*v2*") -> Dict[str, str]:
        checksums = {}
        for p in sorted(directory.glob(pattern)):
            if p.is_file():
                checksums[p.name] = hashlib.sha256(p.read_bytes()).hexdigest()
        return checksums

    def initialize_v2_governance(self):
        checksums_path = ARTIFACTS_DIR / "artifact_checksums_v2.json"
        if checksums_path.exists():
            with open(checksums_path, "r") as f:
                checksums = json.load(f)
        else:
            checksums = self.compute_checksums(ARTIFACTS_DIR, "*v2*")

        metrics_path = ARTIFACTS_DIR / "evaluation_metrics_v2.json"
        baseline_metrics = {}
        if metrics_path.exists():
            with open(metrics_path, "r") as f:
                baseline_metrics = json.load(f)

        self.db.record_governance(
            version_id=MODEL_VERSION,
            artifact_checksums=checksums,
            training_period="January, February, and July 2024 BTS Domestic Flights (1,701,105 rows)",
            validation_period="February 2024 Held-Out Partition (50,000 rows)",
            test_period="July 2024 Held-Out Summer Convective Season (50,000 rows)",
            schema_version="2.0.0 (79 Leakage-Free Observable Features)",
            baseline_metrics=baseline_metrics,
            supported_geography="334 Verified US Domestic Commercial Airports (BTS)",
            supported_carriers="15 US Reporting Airlines (WN, AA, DL, UA, OO, YX, MQ, NK, B6, AS, 9E, OH, F9, G4, HA)",
            deployment_status="ACTIVE_PRODUCTION_EXPERIMENTAL",
            approved_by="Flyo Governance Committee & Phase 3.1 Verification Gate",
            approval_notes="Approved with mandatory operator rebooking approval and strict geofencing."
        )

    def check_rollback_readiness(self) -> Dict[str, Any]:
        """
        Verifies whether v1 backup artifacts exist and can be safely rolled back to if needed.
        Does not overwrite v2 artifacts.
        """
        v1_dir = ARTIFACTS_DIR / "v1_backup"
        if not v1_dir.exists():
            return {
                "rollback_possible": False,
                "reason": "v1_backup directory does not exist in ml/artifacts/"
            }

        required_v1_files = [
            "calibrated_cancellation_v1.joblib",
            "calibrated_delay120_v1.joblib",
            "preprocessor_v1.joblib",
            "xgb_delay_regressor_v1.json"
        ]

        missing = [f for f in required_v1_files if not (v1_dir / f).exists()]
        if missing:
            return {
                "rollback_possible": False,
                "reason": f"Missing v1 backup artifacts: {missing}"
            }

        v1_checksums = self.compute_checksums(v1_dir, "*v1*")
        return {
            "rollback_possible": True,
            "target_version": "v1.0.0-baseline",
            "v1_artifacts_verified": list(v1_checksums.keys()),
            "v1_checksums": v1_checksums,
            "safety_directive": "Rollback requires explicit operator command; never triggers autonomously."
        }

    def get_governance_report(self) -> Dict[str, Any]:
        records = self.db.get_governance_records()
        rollback_status = self.check_rollback_readiness()
        return {
            "active_version": MODEL_VERSION,
            "governance_history": records,
            "rollback_readiness": rollback_status
        }
