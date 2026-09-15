# ADR 0012: Transactional emulator environment application and restoration

## Status

Accepted.

## Decision

`VERIFY_ONLY` remains the default. `APPLY_AND_RESTORE` is explicit and requires an environment contract and the explicitly selected emulator serial which passed emulator/user-0 preflight. Before mutation DroidProof captures bounded locale, `accelerometer_rotation`, `user_rotation`, and the three supported animation scales. It applies only those settings (portrait uses rotation 0 and landscape rotation 1), verifies the existing contract evaluator reports `MATCHED`, and restores the exact snapshot after execution.

Locale uses the narrow `cmd locale set` emulator mechanism. If it is unsupported, the transaction is unavailable; DroidProof does not use root, reboot, service restarts, or arbitrary shell execution. Restore commands and verification are bounded. Successful commands alone are not evidence: the restored low-level snapshot must equal the original snapshot.

Restoration is attempted after ordinary behavioral and infrastructure failures and cancellation once mutation has begun. A restoration mismatch or unavailable observation makes the run unsuccessful. Network cleanup and environment restoration are independent cleanup obligations; neither is evidence that the other ran. `environment/transaction.json` is inventoried like other evidence and is rendered only after bundle verification.

## Limits

This is not emulator provisioning or lifecycle management. It cannot guarantee rollback after SIGKILL, power loss, emulator crash, or permanent ADB loss, and the in-process serial boundary cannot prevent Android Studio, humans, or external processes from changing the emulator concurrently. It covers no AVD creation, snapshots, clock, timezone, permissions, density, fonts, or arbitrary settings.
