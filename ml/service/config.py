from pathlib import Path
from typing import Set

BASE_DIR = Path(__file__).resolve().parent.parent
ARTIFACTS_DIR = BASE_DIR / "artifacts"
DATA_DIR = BASE_DIR / "data"

# Service Configuration
HOST = "0.0.0.0"
PORT = 8000
MODEL_VERSION = "v3.0.0-enterprise"

# Supported Carriers in v3 Engine (Indian + US Baseline)
SUPPORTED_CARRIERS: Set[str] = {
    # Indian Priority Carriers
    "6E", "AI", "UK", "SG", "QP",
    # US Reporting Carriers (BTS)
    "WN", "AA", "DL", "UA", "OO", "YX", "MQ", "NK", "B6", "AS", "9E", "OH", "F9", "G4", "HA"
}

# Top Hubs with Full Operational Prior Representation
TOP_HUBS: Set[str] = {
    # Indian Metro Hubs
    "DEL", "BOM", "BLR", "HYD", "MAA", "CCU",
    # US Major Hubs
    "ATL", "ORD", "DFW", "DEN", "CLT", "LAX", "PHX", "MCO", "LAS", "SEA",
    "MIA", "IAH", "JFK", "EWR", "FLL", "MSP", "SFO", "DTW", "BOS", "SLC"
}

# Indian Domestic Airport Codes
INDIAN_AIRPORT_CODES: Set[str] = {
    "DEL", "BOM", "BLR", "HYD", "MAA", "CCU", "AMD", "GOI", "PNQ", "COK",
    "ATQ", "IXC", "JAI", "LKO", "GAU", "PAT", "VNS", "BBI", "TRV", "IXR"
}

# Priority Indian Domestic Flight Corridors
SUPPORTED_INDIAN_CORRIDORS: Set[str] = {
    "HYD-DEL", "DEL-HYD",
    "HYD-BOM", "BOM-HYD",
    "HYD-BLR", "BLR-HYD",
    "DEL-BOM", "BOM-DEL",
    "BLR-MAA", "MAA-BLR",
    "BOM-BLR", "BLR-BOM"
}
