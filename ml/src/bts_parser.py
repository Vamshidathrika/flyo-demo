import csv
import io
import json
import logging
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from .config import PROCESSED_DATA_DIR, RAW_DATA_DIR, REQUIRED_BTS_COLUMNS

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("BTSParser")

class BTSParser:
    """
    Streaming BTS Archive Parser.
    Extracts and normalizes all domestic flight records directly from BTS ZIP archives
    without loading full uncompressed CSV files into RAM.
    """

    def __init__(self, raw_dir: Path = RAW_DATA_DIR, processed_dir: Path = PROCESSED_DATA_DIR):
        self.raw_dir = raw_dir
        self.processed_dir = processed_dir
        self.processed_dir.mkdir(parents=True, exist_ok=True)

    def format_hhmm(self, val: str) -> Optional[str]:
        if not val or val.strip() == "" or val.strip() == "2400":
            return "00:00" if val and val.strip() == "2400" else None
        cleaned = val.strip().split(".")[0].zfill(4)
        if len(cleaned) == 4 and cleaned[:2].isdigit() and cleaned[2:].isdigit():
            h, m = int(cleaned[:2]), int(cleaned[2:])
            if 0 <= h <= 23 and 0 <= m <= 59:
                return f"{h:02d}:{m:02d}"
        return None

    def parse_float(self, val: str) -> Optional[float]:
        if not val or val.strip() == "":
            return None
        try:
            return float(val.strip())
        except ValueError:
            return None

    def parse_int(self, val: str) -> Optional[int]:
        if not val or val.strip() == "":
            return None
        try:
            return int(float(val.strip()))
        except ValueError:
            return None

    def process_zip_archive(self, zip_path: Path, max_rows: Optional[int] = None) -> Tuple[Path, Dict]:
        if not zip_path.exists():
            raise FileNotFoundError(f"Raw BTS archive not found: {zip_path}")

        logger.info(f"[Parsing Starting] Processing archive: {zip_path.name}")
        year_month = zip_path.stem.split("_")[-2:]
        year, month = int(year_month[0]), int(year_month[1])
        output_csv = self.processed_dir / f"flights_{year}_{month:02d}.csv"
        stats_file = self.processed_dir / f"quality_stats_{year}_{month:02d}.json"

        stats = {
            "archive_filename": zip_path.name,
            "processed_at_utc": datetime.now(timezone.utc).isoformat(),
            "total_rows_read": 0,
            "valid_rows_retained": 0,
            "cancelled_count": 0,
            "diverted_count": 0,
            "completed_count": 0,
            "cancellation_rate_percent": 0.0,
            "cancellation_causes": {"A_Carrier": 0, "B_Weather": 0, "C_NAS": 0, "D_Security": 0, "Unknown": 0},
            "carrier_distribution": {},
            "origin_airport_distribution": {},
            "missing_key_fields": 0,
            "duplicate_records_filtered": 0,
            "delayed_over_15m_count": 0,
            "delayed_over_120m_count": 0,
            "delay_sum_minutes": 0.0,
            "delay_count": 0,
            "max_arr_delay": -999.0,
            "min_arr_delay": 9999.0
        }

        seen_flight_signatures = set()

        processed_fieldnames = [
            "flight_id",
            "flight_date",
            "year",
            "month",
            "day_of_month",
            "day_of_week",
            "carrier_code",
            "tail_number",
            "flight_number",
            "origin_airport",
            "origin_city",
            "origin_state",
            "dest_airport",
            "dest_city",
            "dest_state",
            "scheduled_dep_time",
            "actual_dep_time",
            "dep_delay_minutes",
            "scheduled_arr_time",
            "actual_arr_time",
            "arr_delay_minutes",
            "is_delayed_15m",
            "is_delayed_120m",
            "is_cancelled",
            "cancellation_code",
            "cancellation_reason",
            "is_diverted",
            "scheduled_elapsed_minutes",
            "actual_elapsed_minutes",
            "distance_miles"
        ]

        with zipfile.ZipFile(zip_path, "r") as z:
            csv_filenames = [f for f in z.namelist() if f.endswith(".csv")]
            if not csv_filenames:
                raise ValueError(f"No CSV file found inside archive: {zip_path.name}")

            target_csv = csv_filenames[0]
            logger.info(f"Extracting and streaming from {target_csv} inside {zip_path.name}")

            with z.open(target_csv) as raw_bytes, open(output_csv, "w", newline="", encoding="utf-8") as out_f:
                text_stream = io.TextIOWrapper(raw_bytes, encoding="utf-8", errors="replace")
                reader = csv.DictReader(text_stream)
                writer = csv.DictWriter(out_f, fieldnames=processed_fieldnames)
                writer.writeheader()

                for row in reader:
                    stats["total_rows_read"] += 1

                    if max_rows is not None and stats["valid_rows_retained"] >= max_rows:
                        logger.info(f"Reached max_rows limit of {max_rows}. Stopping parse.")
                        break

                    # Required identification fields
                    carrier = row.get("Reporting_Airline", "").strip() or row.get("IATA_CODE_Reporting_Airline", "").strip()
                    flight_num = self.parse_int(row.get("Flight_Number_Reporting_Airline", ""))
                    origin = row.get("Origin", "").strip()
                    dest = row.get("Dest", "").strip()
                    crs_dep = self.format_hhmm(row.get("CRSDepTime", ""))
                    crs_arr = self.format_hhmm(row.get("CRSArrTime", ""))
                    date_str = row.get("FlightDate", "").strip()

                    # Filter out records missing critical identifying metadata
                    if not (carrier and flight_num and origin and dest and crs_dep and crs_arr and date_str):
                        stats["missing_key_fields"] += 1
                        continue

                    # Deduplication signature
                    sig = (carrier, flight_num, date_str, origin, dest, crs_dep)
                    if sig in seen_flight_signatures:
                        stats["duplicate_records_filtered"] += 1
                        continue
                    if len(seen_flight_signatures) < 1_000_000:
                        seen_flight_signatures.add(sig)

                    # Update carrier and airport distributions
                    stats["carrier_distribution"][carrier] = stats["carrier_distribution"].get(carrier, 0) + 1
                    stats["origin_airport_distribution"][origin] = stats["origin_airport_distribution"].get(origin, 0) + 1

                    # Cancellation tracking
                    is_cancelled = self.parse_int(row.get("Cancelled", "0")) or 0
                    cancel_code = row.get("CancellationCode", "").strip() if is_cancelled == 1 else ""
                    cancel_reason = "None"

                    # Diverted tracking
                    is_diverted = self.parse_int(row.get("Diverted", "0")) or 0

                    # Delay calculations
                    dep_delay = self.parse_float(row.get("DepDelay", ""))
                    arr_delay = self.parse_float(row.get("ArrDelay", ""))

                    if is_cancelled == 1:
                        stats["cancelled_count"] += 1
                        if cancel_code == "A":
                            stats["cancellation_causes"]["A_Carrier"] += 1
                            cancel_reason = "Carrier Cancellation (Mechanical / Crew / Ops)"
                        elif cancel_code == "B":
                            stats["cancellation_causes"]["B_Weather"] += 1
                            cancel_reason = "Weather Cancellation"
                        elif cancel_code == "C":
                            stats["cancellation_causes"]["C_NAS"] += 1
                            cancel_reason = "National Airspace System / ATC Congestion"
                        elif cancel_code == "D":
                            stats["cancellation_causes"]["D_Security"] += 1
                            cancel_reason = "Security Disruption"
                        else:
                            stats["cancellation_causes"]["Unknown"] += 1
                            cancel_reason = "Unspecified Cancellation"
                    elif is_diverted == 1:
                        stats["diverted_count"] += 1
                    else:
                        stats["completed_count"] += 1
                        if arr_delay is not None:
                            stats["delay_sum_minutes"] += arr_delay
                            stats["delay_count"] += 1
                            if arr_delay > stats["max_arr_delay"]:
                                stats["max_arr_delay"] = arr_delay
                            if arr_delay < stats["min_arr_delay"]:
                                stats["min_arr_delay"] = arr_delay
                            if arr_delay >= 15:
                                stats["delayed_over_15m_count"] += 1
                            if arr_delay >= 120:
                                stats["delayed_over_120m_count"] += 1

                    sched_elapsed = self.parse_float(row.get("CRSElapsedTime", ""))
                    actual_elapsed = self.parse_float(row.get("ActualElapsedTime", ""))
                    distance = self.parse_float(row.get("Distance", ""))

                    norm_record = {
                        "flight_id": f"BTS-{year}{month:02d}-{stats['valid_rows_retained']+1:07d}",
                        "flight_date": date_str,
                        "year": year,
                        "month": month,
                        "day_of_month": self.parse_int(row.get("DayofMonth", "1")),
                        "day_of_week": self.parse_int(row.get("DayOfWeek", "1")),
                        "carrier_code": carrier,
                        "tail_number": row.get("Tail_Number", "").strip(),
                        "flight_number": flight_num,
                        "origin_airport": origin,
                        "origin_city": row.get("OriginCityName", "").strip(),
                        "origin_state": row.get("OriginState", "").strip(),
                        "dest_airport": dest,
                        "dest_city": row.get("DestCityName", "").strip(),
                        "dest_state": row.get("DestState", "").strip(),
                        "scheduled_dep_time": crs_dep,
                        "actual_dep_time": self.format_hhmm(row.get("DepTime", "")) if is_cancelled == 0 else None,
                        "dep_delay_minutes": dep_delay if is_cancelled == 0 else None,
                        "scheduled_arr_time": crs_arr,
                        "actual_arr_time": self.format_hhmm(row.get("ArrTime", "")) if is_cancelled == 0 else None,
                        "arr_delay_minutes": arr_delay if is_cancelled == 0 else None,
                        "is_delayed_15m": 1 if (arr_delay is not None and arr_delay >= 15) else 0,
                        "is_delayed_120m": 1 if (arr_delay is not None and arr_delay >= 120) else 0,
                        "is_cancelled": is_cancelled,
                        "cancellation_code": cancel_code if is_cancelled == 1 else "None",
                        "cancellation_reason": cancel_reason,
                        "is_diverted": is_diverted,
                        "scheduled_elapsed_minutes": sched_elapsed,
                        "actual_elapsed_minutes": actual_elapsed if is_cancelled == 0 else None,
                        "distance_miles": distance
                    }

                    writer.writerow(norm_record)
                    stats["valid_rows_retained"] += 1

                    if stats["valid_rows_retained"] % 100000 == 0:
                        logger.info(f"Streamed {stats['valid_rows_retained']:,} records...")

        # Calculate final metrics
        if stats["valid_rows_retained"] > 0:
            stats["cancellation_rate_percent"] = round((stats["cancelled_count"] / stats["valid_rows_retained"]) * 100, 3)

        if stats["delay_count"] > 0:
            stats["delay_metrics"] = {
                "count_with_arr_delay": stats["delay_count"],
                "mean_arr_delay_minutes": round(stats["delay_sum_minutes"] / stats["delay_count"], 2),
                "max_arr_delay_minutes": round(stats["max_arr_delay"], 1),
                "min_arr_delay_minutes": round(stats["min_arr_delay"], 1),
                "on_time_rate_under_15m_percent": round(((stats["delay_count"] - stats["delayed_over_15m_count"]) / stats["delay_count"]) * 100, 2),
                "extreme_delay_over_120m_rate_percent": round((stats["delayed_over_120m_count"] / stats["delay_count"]) * 100, 2)
            }

        # Sort distributions by count
        stats["carrier_distribution"] = dict(sorted(stats["carrier_distribution"].items(), key=lambda x: x[1], reverse=True))
        stats["origin_airport_distribution"] = dict(sorted(stats["origin_airport_distribution"].items(), key=lambda x: x[1], reverse=True)[:50])

        with open(stats_file, "w", encoding="utf-8") as f:
            json.dump(stats, f, indent=2)

        logger.info(f"[Parsing Completed] Retained {stats['valid_rows_retained']:,} valid flights ({stats['cancelled_count']:,} cancelled, {stats['cancellation_rate_percent']}%) -> {output_csv}")
        return output_csv, stats

if __name__ == "__main__":
    parser = BTSParser()
    zip_p = RAW_DATA_DIR / "On_Time_Reporting_Carrier_On_Time_Performance_1987_present_2024_1.zip"
    if zip_p.exists():
        parser.process_zip_archive(zip_p)
