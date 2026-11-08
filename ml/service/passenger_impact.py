"""
FLYO STAGE 8 — PASSENGER IMPACT INTELLIGENCE ENGINE
Estimates flight passenger volume, connection vulnerability, rebooking demand,
and airline rescue workload under predicted disruption scenarios.
"""

from __future__ import annotations

import logging
from typing import Any, Dict, Optional

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("PassengerImpactEngine")

# Standard Aircraft Gauge Capacities
AIRCRAFT_CAPACITIES: Dict[str, int] = {
    "A320": 180,
    "A321": 220,
    "B737": 186,
    "B738": 189,
    "B787": 296,
    "B777": 368,
    "ATR72": 72,
    "DEFAULT": 180,
}

class PassengerImpactEngine:
    """
    Translates flight disruption probabilities and delay forecasts into operational
    passenger impact metrics, rebooking demand, and rescue workload estimates.
    """

    def estimate_flight_impact(
        self,
        flight_number: str,
        carrier_code: str,
        origin_airport: str,
        dest_airport: str,
        disruption_probability: float,
        expected_delay_minutes: float,
        is_cancelled: bool = False,
        aircraft_type: str = "A320",
        load_factor: float = 0.85,
        connecting_passenger_ratio: Optional[float] = None,
        elite_passenger_ratio: float = 0.12,
    ) -> Dict[str, Any]:
        capacity = AIRCRAFT_CAPACITIES.get(aircraft_type.upper(), AIRCRAFT_CAPACITIES["DEFAULT"])
        total_pax = int(round(capacity * load_factor))

        # Hubs have higher connection ratios (e.g. DEL, BOM, ORD, ATL)
        hub_airports = {"DEL", "BOM", "BLR", "ORD", "ATL", "DFW", "DEN", "JFK"}
        if connecting_passenger_ratio is None:
            if dest_airport.upper() in hub_airports or origin_airport.upper() in hub_airports:
                conn_ratio = 0.35 # 35% connecting at primary hubs
            else:
                conn_ratio = 0.15 # 15% point-to-point
        else:
            conn_ratio = connecting_passenger_ratio

        connecting_pax = int(round(total_pax * conn_ratio))
        point_to_point_pax = total_pax - connecting_pax
        elite_pax = int(round(total_pax * elite_passenger_ratio))

        # Rebooking Demand Calculation
        if is_cancelled or disruption_probability >= 0.70:
            # 100% of passengers require rebooking or refund
            rebooking_demand = total_pax
            vulnerable_connections = connecting_pax
            workload_hours = round(total_pax * 0.25, 1) # ~15 min per case
            severity = "CRITICAL"
            cost_tier = "CRITICAL_EXPOSURE"
        elif expected_delay_minutes >= 120.0 or disruption_probability >= 0.40:
            # All connecting passengers miss connections + 15% local dropouts
            vulnerable_connections = int(round(connecting_pax * 0.90))
            rebooking_demand = vulnerable_connections + int(round(point_to_point_pax * 0.15))
            workload_hours = round(rebooking_demand * 0.20, 1)
            severity = "HIGH"
            cost_tier = "HIGH_EXPOSURE"
        elif expected_delay_minutes >= 45.0 or disruption_probability >= 0.20:
            # 40% of tight connections break
            vulnerable_connections = int(round(connecting_pax * 0.40))
            rebooking_demand = vulnerable_connections
            workload_hours = round(rebooking_demand * 0.15, 1)
            severity = "MODERATE"
            cost_tier = "MODERATE_EXPOSURE"
        else:
            vulnerable_connections = int(round(connecting_pax * 0.05))
            rebooking_demand = vulnerable_connections
            workload_hours = round(max(0.5, rebooking_demand * 0.10), 1)
            severity = "LOW"
            cost_tier = "LOW_EXPOSURE"

        return {
            "flight_number": flight_number,
            "carrier_code": carrier_code,
            "origin_airport": origin_airport,
            "dest_airport": dest_airport,
            "aircraft_type": aircraft_type,
            "seat_capacity": capacity,
            "load_factor": round(load_factor, 2),
            "estimated_passengers_total": total_pax,
            "estimated_connecting_passengers": connecting_pax,
            "estimated_point_to_point_passengers": point_to_point_pax,
            "estimated_elite_passengers": elite_pax,
            "vulnerable_connections_count": vulnerable_connections,
            "projected_rebooking_demand": rebooking_demand,
            "estimated_rescue_workload_hours": workload_hours,
            "impact_severity": severity,
            "cost_exposure_tier": cost_tier,
            "disclaimer": (
                "STATISTICAL ESTIMATE: Passenger metrics are derived from aircraft seating gauge, "
                "industry load factors, and hub connectivity distributions. This does not represent "
                "authoritative carrier PNR manifest data."
            ),
        }
