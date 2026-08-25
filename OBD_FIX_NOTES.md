# SmartMatrixOBD OBD/ECU communication fix

This is the existing SmartMatrixOBD project with the communication/readout layer repaired.

## Root causes found in the original code
- Bluetooth socket success was reported as OBD/ECU success without any ECU-level request.
- `ATSP0` was accepted as if it proved ECU communication; it only configures the ELM327 adapter.
- No `0100` ECU handshake was performed.
- MAF was requested as `010F` (IAT) instead of `0110` (MAF).
- PID 0D (vehicle speed) was incorrectly assigned into the MAP field in the parser.
- O2, battery voltage, DTC and pending-DTC requests were absent.
- The parser required a simplistic `41` token and did not robustly locate the requested PID in real adapter responses.
- STFT/LTFT used zero as an implicit "not available" sentinel, making a valid 0% reading impossible to distinguish from no response.
- Raw command/response logging was not implemented.

## What changed
- ELM327 initialization now waits for responses and validates adapter behavior.
- ECU readiness is proven by a positive response to `0100` (`41 00 ...`).
- Live data uses the correct standard PIDs and formulas.
- DTC mode 03 and pending DTC mode 07 are read and decoded.
- Invalid/unavailable readings are rendered as `غير متاح`, never as fake -1 values.
- The existing UI is retained; only the data/log/status content was extended.
- Gradle versions, minSdk, targetSdk and Manifest permissions were not changed.

## Important limitation
The code can prove the adapter and ECU transaction path, but it cannot manufacture a PID that a vehicle/ECU does not support. A vehicle-specific PID that is not standard OBD-II remains unavailable by design.
