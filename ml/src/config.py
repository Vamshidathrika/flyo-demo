import os
from pathlib import Path

# Base directories
BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = BASE_DIR / "data"
RAW_DATA_DIR = DATA_DIR / "raw"
PROCESSED_DATA_DIR = DATA_DIR / "processed"
WEATHER_DATA_DIR = DATA_DIR / "weather"

# Ensure directories exist
for d in [DATA_DIR, RAW_DATA_DIR, PROCESSED_DATA_DIR, WEATHER_DATA_DIR]:
    d.mkdir(parents=True, exist_ok=True)

# BTS Data Configuration
BTS_BASE_URL = "https://www.transtats.bts.gov/PREZIP"
BTS_FILE_TEMPLATE = "On_Time_Reporting_Carrier_On_Time_Performance_1987_present_{year}_{month}.zip"

# Core flight columns required for XGBoost delay & cancellation modeling
REQUIRED_BTS_COLUMNS = [
    "Year",
    "Month",
    "DayofMonth",
    "DayOfWeek",
    "FlightDate",
    "Reporting_Airline",
    "DOT_ID_Reporting_Airline",
    "IATA_CODE_Reporting_Airline",
    "Tail_Number",
    "Flight_Number_Reporting_Airline",
    "OriginAirportID",
    "Origin",
    "OriginCityName",
    "OriginState",
    "DestAirportID",
    "Dest",
    "DestCityName",
    "DestState",
    "CRSDepTime",     # Scheduled departure (HHMM)
    "DepTime",        # Actual departure (HHMM)
    "DepDelay",       # Departure delay (minutes)
    "DepDelayMinutes",
    "DepDel15",       # Departure delayed 15+ min (1/0)
    "CRSArrTime",     # Scheduled arrival (HHMM)
    "ArrTime",        # Actual arrival (HHMM)
    "ArrDelay",       # Arrival delay (minutes) - PRIMARY REGRESSION TARGET
    "ArrDelayMinutes",
    "ArrDel15",       # Arrival delayed 15+ min (1/0)
    "Cancelled",      # Cancelled flight (1/0) - PRIMARY CLASSIFICATION TARGET
    "CancellationCode", # Reason (A: Carrier, B: Weather, C: NAS, D: Security)
    "Diverted",
    "CRSElapsedTime", # Scheduled flight duration (minutes)
    "ActualElapsedTime",
    "AirTime",
    "Distance",       # Flight distance in miles
    "CarrierDelay",
    "WeatherDelay",
    "NASDelay",
    "SecurityDelay",
    "LateAircraftDelay"
]

# Major US Airport Coordinates for Open-Meteo Historical Archive Matching
MAJOR_US_AIRPORTS = {
    "ATL": {"name": "Hartsfield-Jackson Atlanta", "lat": 33.6407, "lon": -84.4277},
    "ORD": {"name": "Chicago O'Hare", "lat": 41.9742, "lon": -87.9073},
    "DFW": {"name": "Dallas/Fort Worth", "lat": 32.8998, "lon": -97.0403},
    "DEN": {"name": "Denver International", "lat": 39.8561, "lon": -104.6737},
    "LAX": {"name": "Los Angeles International", "lat": 33.9416, "lon": -118.4085},
    "JFK": {"name": "John F. Kennedy International", "lat": 40.6413, "lon": -73.7781},
    "SFO": {"name": "San Francisco International", "lat": 37.6213, "lon": -122.3790},
    "SEA": {"name": "Seattle-Tacoma", "lat": 47.4502, "lon": -122.3088},
    "MIA": {"name": "Miami International", "lat": 25.7959, "lon": -80.2870},
    "BOS": {"name": "Boston Logan", "lat": 42.3656, "lon": -71.0096},
    "EWR": {"name": "Newark Liberty", "lat": 40.6895, "lon": -74.1745},
    "CLT": {"name": "Charlotte Douglas", "lat": 35.2144, "lon": -80.9473},
    "PHX": {"name": "Phoenix Sky Harbor", "lat": 33.4373, "lon": -112.0078},
    "IAH": {"name": "George Bush Houston", "lat": 29.9902, "lon": -95.3368},
    "LGA": {"name": "LaGuardia New York", "lat": 40.7769, "lon": -73.8740}
}
