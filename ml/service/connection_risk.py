"""
FLYO STAGE 8 — PASSENGER CONNECTION RISK ENGINE
Computes connection miss probability, available transfer margin, and actionable
operational interventions based on inbound delay forecasts, airport MCTs, and terminal transfers.
"""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Tuple

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("ConnectionRiskEngine")

# Standard Minimum Connection Times (Minutes)
AIRPORT_MCT_RULES: Dict[str, Dict[str, int]] = {
    "DEL": {"intra_terminal": 45, "inter_terminal": 75, "intl_transfer": 90},
    "BOM": {"intra_terminal": 45, "inter_terminal": 90, "intl_transfer": 90},
    "BLR": {"intra_terminal": 40, "inter_terminal": 60, "intl_transfer": 75},
    "HYD": {"intra_terminal": 35, "inter_terminal": 45, "intl_transfer": 60},
    "MAA": {"intra_terminal": 40, "inter_terminal": 60, "intl_transfer": 75},
    "CCU": {"intra_terminal": 40, "inter_terminal": 45, "intl_transfer": 60},
    "ORD": {"intra_terminal": 50, "inter_terminal": 75, "intl_transfer": 90},
    "ATL": {"intra_terminal": 45, "inter_terminal": 60, "intl_transfer": 85},
    "DFW": {"intra_terminal": 45, "inter_terminal": 60, "intl_transfer": 80},
    "DEN": {"intra_terminal": 40, "inter_terminal": 50, "intl_transfer": 75},
    "DEFAULT": {"intra_terminal": 45, "inter_terminal": 70, "intl_transfer": 85},
}

class ConnectionRiskEngine:
    """
    Evaluates connecting passenger itineraries and calculates probabilistic failure risk.
    """

    def evaluate_connection(
        self,
        airport_code: str,
        scheduled_connection_minutes: int,
        predicted_inbound_delay_minutes: float,
        inbound_terminal: Optional[str] = None,
        outbound_terminal: Optional[str] = None,
        is_international: bool = False,
        has_checked_baggage: bool = True,
        passenger_count: int = 1,
    ) -> Dict[str, Any]:
        apt = airport_code.upper()
        rules = AIRPORT_MCT_RULES.get(apt, AIRPORT_MCT_RULES["DEFAULT"])

        # Determine effective Minimum Connection Time (MCT)
        is_inter_terminal = (
            inbound_terminal is not None
            and outbound_terminal is not None
            and inbound_terminal.upper() != outbound_terminal.upper()
        )

        if is_international:
            base_mct = rules["intl_transfer"]
        elif is_inter_terminal:
            base_mct = rules["inter_terminal"]
        else:
            base_mct = rules["intra_terminal"]

        # Additional buffer for baggage transfer or congestion
        baggage_buffer = 10 if has_checked_baggage else 0
        effective_mct = base_mct + baggage_buffer

        # Available time left for passenger after inbound delay
        available_window = scheduled_connection_minutes - predicted_inbound_delay_minutes
        connection_margin = available_window - effective_mct

        # Probabilistic failure calculation using smooth logistic function
        # Margin = 0 means ~50% probability; negative margin rapidly approaches 1.0
        k = 0.12 # Steepness parameter
        failure_prob = 1.0 / (1.0 + np.exp(k * connection_margin))
        failure_prob = float(np.clip(failure_prob, 0.01, 0.99))

        # Risk Classification & Operational Recommendation
        if connection_margin <= -15.0 or failure_prob >= 0.85:
            risk_tier = "CRITICAL"
            recommended_action = "PROACTIVE_REBOOKING_HOLD"
            action_desc = "Connection is physically unviable. Protect passenger on next available departure immediately."
        elif -15.0 < connection_margin <= 0.0 or failure_prob >= 0.50:
            risk_tier = "HIGH"
            recommended_action = "EXPEDITED_GATE_ESCORT"
            action_desc = "Connection is at severe risk. Dispatch ramp agent for direct tarmac/terminal transfer."
        elif 0.0 < connection_margin <= 20.0 or failure_prob >= 0.25:
            risk_tier = "MODERATE"
            recommended_action = "MONITOR_INBOUND_DESCENT"
            action_desc = "Tight connection window. Monitor inbound aircraft touchdown and assign priority baggage offloading."
        else:
            risk_tier = "LOW"
            recommended_action = "STANDARD_MONITORING"
            action_desc = "Sufficient connection buffer available under current delay forecast."

        return {
            "airport_code": apt,
            "scheduled_connection_minutes": scheduled_connection_minutes,
            "predicted_inbound_delay_minutes": round(predicted_inbound_delay_minutes, 1),
            "effective_mct_minutes": effective_mct,
            "is_inter_terminal_transfer": is_inter_terminal,
            "available_window_minutes": round(available_window, 1),
            "connection_margin_minutes": round(connection_margin, 1),
            "connection_failure_probability": round(failure_prob, 4),
            "risk_tier": risk_tier,
            "recommended_action": recommended_action,
            "action_description": action_desc,
            "passenger_count": passenger_count,
        }

import numpy as np
