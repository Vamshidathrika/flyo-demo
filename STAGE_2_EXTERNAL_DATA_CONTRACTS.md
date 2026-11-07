# Stage 2: External Data Contracts Specification

**Author:** Enterprise Integration Architect & API Designer  
**Date:** November 6, 2026  
**System:** Flyo Autonomous Disruption Recovery Engine  

---

## 1. Flight Status Ingestion Contract (JSON Schema & Sample)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "FlightStatusUpdateEvent",
  "type": "object",
  "required": ["eventId", "eventType", "timestamp", "tenantId", "payload"],
  "properties": {
    "eventId": { "type": "string" },
    "eventType": { "type": "string", "enum": ["FLIGHT_STATUS_UPDATE"] },
    "timestamp": { "type": "string", "format": "date-time" },
    "tenantId": { "type": "string" },
    "payload": {
      "type": "object",
      "required": ["flightNumber", "departureDate", "origin", "destination", "status"],
      "properties": {
        "flightNumber": { "type": "string", "pattern": "^[A-Z0-9]{2,3}-[0-9]{1,4}$" },
        "departureDate": { "type": "string", "format": "date" },
        "origin": { "type": "string", "pattern": "^[A-Z]{3}$" },
        "destination": { "type": "string", "pattern": "^[A-Z]{3}$" },
        "status": { "type": "string", "enum": ["SCHEDULED", "ON_TIME", "DELAYED", "CANCELLED", "DIVERTED"] },
        "delayMinutes": { "type": "integer", "minimum": 0 },
        "disruptionReason": { "type": "string" },
        "diversionAirport": { "type": "string" }
      }
    }
  }
}
```

---

## 2. Passenger PNR Lookup Contract

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "PnrLookupResponse",
  "type": "object",
  "required": ["pnr", "passengerName", "segments", "bookingStatus"],
  "properties": {
    "pnr": { "type": "string", "minLength": 6, "maxLength": 6 },
    "passengerName": { "type": "string" },
    "passengerPhone": { "type": "string", "pattern": "^\\+[1-9][0-9]{7,14}$" },
    "passengerEmail": { "type": "string", "format": "email" },
    "vipTier": { "type": "string", "enum": ["STANDARD", "TIER_2_PRIORITY", "TIER_1_VIP"] },
    "segments": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["segmentIndex", "flightNumber", "origin", "destination", "departureTime", "couponStatus"],
        "properties": {
          "segmentIndex": { "type": "integer" },
          "flightNumber": { "type": "string" },
          "origin": { "type": "string" },
          "destination": { "type": "string" },
          "departureTime": { "type": "string", "format": "date-time" },
          "arrivalTime": { "type": "string", "format": "date-time" },
          "ticketNumber": { "type": "string" },
          "couponStatus": { "type": "string", "enum": ["OPEN", "CHECKED_IN", "FLOWN", "VOID", "EXCHANGED"] }
        }
      }
    },
    "bookingStatus": { "type": "string", "enum": ["CONFIRMED", "CANCELLED", "TICKETED", "WAITLISTED"] }
  }
}
```

---

## 3. Rebooking Order Execution Contract (IATA NDC / GDS Adapter)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "RebookExecutionRequest",
  "type": "object",
  "required": ["caseId", "optionId", "targetFlight", "passengerName", "paymentToken", "amount", "currency", "idempotencyKey"],
  "properties": {
    "caseId": { "type": "string" },
    "optionId": { "type": "string" },
    "targetFlight": { "type": "string" },
    "passengerName": { "type": "string" },
    "passengerPhone": { "type": "string" },
    "passengerEmail": { "type": "string" },
    "paymentToken": { "type": "string" },
    "amount": { "type": "integer", "minimum": 0 },
    "currency": { "type": "string", "default": "INR" },
    "idempotencyKey": { "type": "string" }
  }
}
```

---

## 4. Virtual Card Issuance Contract (PCI-DSS Scoped)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "VirtualCardIssuanceResponse",
  "type": "object",
  "required": ["cardToken", "last4", "expiry", "amount", "currency", "status"],
  "properties": {
    "cardToken": { "type": "string", "description": "PCI-compliant opaque token" },
    "last4": { "type": "string", "pattern": "^[0-9]{4}$" },
    "expiry": { "type": "string", "pattern": "^(0[1-9]|1[0-2])\\/[0-9]{2}$" },
    "amount": { "type": "integer" },
    "currency": { "type": "string" },
    "status": { "type": "string", "enum": ["ACTIVE", "LOCKED", "SETTLED", "CANCELLED"] },
    "merchantCategoryLock": { "type": "string", "default": "3000-3350" }
  }
}
```
> **Security Note:** Raw PAN and CVV fields are strictly absent from all contracts to guarantee zero cardholder data exposure.
