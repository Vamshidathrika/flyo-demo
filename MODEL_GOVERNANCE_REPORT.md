# Model Governance & Rollback Readiness Report

**Flyo Disruption Rescue Platform &middot; Phase 4 Engineering Verification**  
**Module:** `ml/service/governance.py`  
**Active Model Version:** `v2.0.0-multiseason`  
**Rollback Target:** `v1_backup`  
**Checksum Registry:** `ml/artifacts/artifact_checksums_v2.json`  

---

## 1. Governance & Version Control Invariants

Flyo enforces strict model governance to prevent unauthorized weight tampering, feature drift, or unverified deployments:

1. **Cryptographic Checksum Verification**: Every artifact loaded into memory or deployed to disk is checked against immutable SHA-256 digests.
2. **Feature Schema Immutability**: All models strictly enforce the 79-feature schema defined in `feature_schema_v2.json`. Any mismatch immediately fast-fails during initialization.
3. **Rollback Readiness**: In case of runtime corruption, severe calibration drift, or infrastructure incompatibility, the service maintains instant rollback capability to `v1_backup`.

---

## 2. SHA-256 Artifact Integrity Table

| Artifact File | Description | SHA-256 Checksum (First 16 chars) | Status |
| :--- | :--- | :---: | :---: |
| `cancellation_classifier_v2.joblib` | Calibrated XGBoost cancellation classifier | `e8284560ea5176b5...` | **VERIFIED** |
| `delay120m_classifier_v2.joblib` | Calibrated XGBoost $\ge 120$m delay classifier | `c76fb1b39e6a98dc...` | **VERIFIED** |
| `delay_continuous_regressor_v2.json` | XGBoost continuous delay regressor booster | `89d53b9a7fb3858c...` | **VERIFIED** |
| `preprocessor_v2.joblib` | 79-feature scikit-learn pipeline | `bc4f5ee35d3d04d8...` | **VERIFIED** |
| `feature_schema_v2.json` | JSON schema contract of 79 feature names & types | `3443ee9e5bc5f8b9...` | **VERIFIED** |
| `supported_airports_v2.json` | 334 verified US BTS domestic airport IATA codes | `f40956b9c9451159...` | **VERIFIED** |
| `training_report_v2.json` | Multi-season training summary (1.7M records) | `bb0642fa68c0780f...` | **VERIFIED** |
| `validation_report_v2.json` | Multi-season validation metrics & Brier scores | `6e82cebe3bc278eb...` | **VERIFIED** |
| `baseline_reference_distributions.json` | Baseline drift distributions (July 2024 split) | `ca033d526fa9b42e...` | **VERIFIED** |

---

## 3. Rollback Target & Safety Audit

The `v1_backup` directory (`ml/artifacts/v1_backup/`) contains the complete set of initial single-month v1 models:
- `cancellation_classifier.joblib`
- `delay120m_classifier.joblib`
- `delay_regressor.json`
- `preprocessor.joblib`
- `feature_schema.json`
- `supported_airports.json`

### Rollback Verification Checklist:
- Directory existence: `ml/artifacts/v1_backup` exists.
- File completeness: All 6 required v1 artifacts are present.
- Automated API readiness check: `governance.check_rollback_readiness()` returns `ready: true`.

---

## 4. Governance API Response

Endpoint: `GET /monitoring/governance` (Protected by `X-Flyo-Monitoring-Key`)

```json
{
  "active_version": "v2.0.0-multiseason",
  "training_period": "2024-01-01 to 2024-07-31 (Jan, Apr, Jul multi-season)",
  "total_training_records": 1707271,
  "carrier_coverage_count": 15,
  "airport_coverage_count": 334,
  "artifacts_verified": true,
  "rollback_target": "v1_backup",
  "rollback_ready": true,
  "governance_rule": "Hot reloading of unverified model artifacts is strictly forbidden. Mandatory engineering signoff required."
}
```
