import hashlib
import json
import logging
import os
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Optional

from .config import BTS_BASE_URL, BTS_FILE_TEMPLATE, RAW_DATA_DIR

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("BTSDownloader")

METADATA_FILE = RAW_DATA_DIR / "metadata.json"

class BTSDownloader:

    def __init__(self, raw_dir: Path = RAW_DATA_DIR):
        self.raw_dir = raw_dir
        self.metadata_file = self.raw_dir / "metadata.json"
        self.metadata = self._load_metadata()

    def _load_metadata(self) -> Dict:
        if self.metadata_file.exists():
            try:
                with open(self.metadata_file, "r", encoding="utf-8") as f:
                    return json.load(f)
            except Exception as e:
                logger.warning(f"Could not load metadata file ({e}), initializing empty registry.")
        return {}

    def _save_metadata(self):
        with open(self.metadata_file, "w", encoding="utf-8") as f:
            json.dump(self.metadata, f, indent=2)

    def calculate_sha256(self, filepath: Path) -> str:
        sha256 = hashlib.sha256()
        with open(filepath, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                sha256.update(chunk)
        return sha256.hexdigest()

    def download_month(self, year: int, month: int, force: bool = False) -> Path:
        filename = BTS_FILE_TEMPLATE.format(year=year, month=month)
        url = f"{BTS_BASE_URL}/{filename}"
        target_path = self.raw_dir / filename
        key = f"{year}_{month:02d}"

        # Incremental guard: Check if already downloaded and valid
        if not force and target_path.exists() and key in self.metadata:
            meta = self.metadata[key]
            if target_path.stat().st_size == meta.get("size_bytes", 0):
                logger.info(f"[Incremental Cache Hit] {filename} already exists ({meta['size_bytes']:,} bytes). Skipping re-download.")
                return target_path

        logger.info(f"[Download Starting] Fetching BTS monthly record: {url}")
        start_time = time.time()

        req = urllib.request.Request(
            url,
            headers={
                "User-Agent": "FlyoPredictiveIntelligence/1.0 (Aviation Operational Research)",
                "Accept": "*/*"
            }
        )

        temp_target = target_path.with_suffix(".tmp")
        try:
            with urllib.request.urlopen(req, timeout=60) as response, open(temp_target, "wb") as out_file:
                content_length = response.headers.get("Content-Length")
                expected_size = int(content_length) if content_length else None
                bytes_downloaded = 0
                block_size = 65536

                while True:
                    chunk = response.read(block_size)
                    if not chunk:
                        break
                    out_file.write(chunk)
                    bytes_downloaded += len(chunk)

            temp_target.replace(target_path)
            duration = time.time() - start_time
            file_size = target_path.stat().st_size
            sha256_hash = self.calculate_sha256(target_path)

            logger.info(f"[Download Completed] Saved {filename} ({file_size:,} bytes) in {duration:.2f}s | SHA-256: {sha256_hash[:12]}...")

            self.metadata[key] = {
                "year": year,
                "month": month,
                "filename": filename,
                "source_url": url,
                "downloaded_at_utc": datetime.now(timezone.utc).isoformat(),
                "size_bytes": file_size,
                "sha256": sha256_hash,
                "status": "RAW_DOWNLOAD_SUCCESS"
            }
            self._save_metadata()

            return target_path

        except Exception as e:
            if temp_target.exists():
                temp_target.unlink()
            logger.error(f"[Download Failed] Error fetching {url}: {e}")
            raise RuntimeError(f"BTS download failed for {year}-{month:02d}: {e}") from e

if __name__ == "__main__":
    downloader = BTSDownloader()
    downloader.download_month(2024, 1)
