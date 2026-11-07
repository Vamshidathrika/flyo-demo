from pathlib import Path
from typing import Set

BASE_DIR = Path(__file__).resolve().parent.parent
ARTIFACTS_DIR = BASE_DIR / "artifacts"

# Service Configuration
HOST = "0.0.0.0"
PORT = 8000
MODEL_VERSION = "v2.0.0-multiseason"

# All 15 US reporting carriers in training corpus
SUPPORTED_CARRIERS: Set[str] = {
    "WN", "AA", "DL", "UA", "OO", "YX", "MQ", "NK", "B6", "AS", "9E", "OH", "F9", "G4", "HA"
}

# Top 20 hubs with full historical prior representation
TOP_HUBS: Set[str] = {
    "ATL", "ORD", "DFW", "DEN", "CLT", "LAX", "PHX", "MCO", "LAS", "SEA",
    "MIA", "IAH", "JFK", "EWR", "FLL", "MSP", "SFO", "DTW", "BOS", "SLC"
}

# Indian Domestic Airport Codes (Mandatory Geofencing)
INDIAN_AIRPORT_CODES: Set[str] = {
    "DEL", "BOM", "BLR", "HYD", "MAA", "CCU", "AMD", "GOI", "PNQ", "COK",
    "ATQ", "IXC", "JAI", "LKO", "GAU", "PAT", "VNS", "BBI", "TRV", "IXR"
}
