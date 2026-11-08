import csv
import json
import logging
import sys
from pathlib import Path
from typing import Dict, Optional

from .bts_downloader import BTSDownloader
from .bts_parser import BTSParser
from .config import PROCESSED_DATA_DIR, RAW_DATA_DIR
from .weather_matcher import WeatherMatcher

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("Pipeline")

class IngestionPipeline:

    def __init__(self, raw_dir: Path = RAW_DATA_DIR, processed_dir: Path = PROCESSED_DATA_DIR):
        self.raw_dir = raw_dir
        self.processed_dir = processed_dir
        self.downloader = BTSDownloader(raw_dir=self.raw_dir)
        self.parser = BTSParser(raw_dir=self.raw_dir, processed_dir=self.processed_dir)
        self.weather_matcher = WeatherMatcher()

    def run_ingestion_test(self, year: int = 2024, month: int = 1, parse_max_rows: Optional[int] = 50000, enrich_sample_rows: int = 2500) -> Dict:
        logger.info(f"=== Starting Real-Data Ingestion Pipeline Test ({year}-{month:02d}) ===")

        # Step 1: Download raw BTS monthly zip archive
        zip_path = self.downloader.download_month(year, month)

        # Step 2: Parse and normalize flight records & collect quality stats
        processed_csv, quality_stats = self.parser.process_zip_archive(zip_path, max_rows=parse_max_rows)

        # Step 3: Match historical weather for sample flights (major airports)
        logger.info(f"=== Enriching sample of {enrich_sample_rows} flights with Open-Meteo Historical Weather ===")
        enriched_csv = self.processed_dir / f"enriched_sample_{year}_{month:02d}.csv"

        enriched_count = 0
        weather_matched_count = 0

        with open(processed_csv, "r", encoding="utf-8") as in_f, open(enriched_csv, "w", newline="", encoding="utf-8") as out_f:
            reader = csv.DictReader(in_f)
            fieldnames = list(reader.fieldnames) + [
                "origin_temp_c",
                "origin_weather_code",
                "origin_wind_speed_kmh",
                "dest_temp_c",
                "dest_weather_code",
                "dest_wind_speed_kmh",
                "weather_coverage_status"
            ]
            writer = csv.DictWriter(out_f, fieldnames=fieldnames)
            writer.writeheader()

            for row in reader:
                if enriched_count >= enrich_sample_rows:
                    break

                origin = row["origin_airport"]
                dest = row["dest_airport"]
                f_date = row["flight_date"]
                dep_time = row["scheduled_dep_time"]
                arr_time = row["scheduled_arr_time"]

                # Leakage-Free Weather Lookup (Scheduled time only)
                origin_w = self.weather_matcher.match_scheduled_weather(origin, f_date, dep_time)
                dest_w = self.weather_matcher.match_scheduled_weather(dest, f_date, arr_time)

                has_origin_w = origin_w["is_weather_available"] == 1
                has_dest_w = dest_w["is_weather_available"] == 1

                if has_origin_w or has_dest_w:
                    weather_matched_count += 1

                coverage = "FULL" if (has_origin_w and has_dest_w) else ("ORIGIN_ONLY" if has_origin_w else ("DEST_ONLY" if has_dest_w else "UNAVAILABLE"))

                row["origin_temp_c"] = origin_w["temperature_c"]
                row["origin_weather_code"] = origin_w["weather_code"]
                row["origin_wind_speed_kmh"] = origin_w["wind_speed_kmh"]
                row["dest_temp_c"] = dest_w["temperature_c"]
                row["dest_weather_code"] = dest_w["weather_code"]
                row["dest_wind_speed_kmh"] = dest_w["wind_speed_kmh"]
                row["weather_coverage_status"] = coverage

                writer.writerow(row)
                enriched_count += 1

        quality_stats["sample_enrichment"] = {
            "sample_rows_enriched": enriched_count,
            "weather_matched_count": weather_matched_count,
            "weather_coverage_percent": round((weather_matched_count / enriched_count) * 100, 2) if enriched_count > 0 else 0,
            "enriched_sample_file": str(enriched_csv)
        }

        logger.info(f"=== Pipeline Test Complete: {enriched_count} flights enriched (Weather Match Rate: {quality_stats['sample_enrichment']['weather_coverage_percent']}%) ===")
        return quality_stats

if __name__ == "__main__":
    pipeline = IngestionPipeline()
    stats = pipeline.run_ingestion_test(year=2024, month=1, parse_max_rows=50000, enrich_sample_rows=2500)
    print(json.dumps(stats, indent=2))
