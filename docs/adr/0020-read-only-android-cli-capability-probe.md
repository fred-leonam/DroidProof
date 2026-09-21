# ADR 0020: Read-only Android CLI capability probe

## Status

Accepted.

## Decision

DroidProof has one capability-based Android CLI probe shared by the explicit diagnostic task and the `android-cli` provisioner. The probe validates the configured executable, uses bounded direct process execution, selects the configured SDK with the documented global `--sdk=<absolute-path>` form unless installed help advertises only the separate form, and observes version, global help, emulator command help, and `emulator list`. It never invokes SDK package mutation or emulator create, start, stop, or removal.

The deterministic report has no timestamps. It records bounded version data, host and SDK status, command availability, every provisioning guarantee required by ADR 0018, an overall outcome, and stable issue codes. Non-zero exits can establish incompatibility; process failures, timeouts, truncated output, and ambiguous output remain unverified. Help advertises a command surface but does not prove runtime mutation permission or success.

`legacy` remains the default backend and there is no fallback. `AndroidCliEmulatorProvisioner` must refuse before mutation unless exact image package and revision selection, isolated owned state, clean-state start, deterministic port and serial association, bounded stop, safe owned removal, rollback, and runtime API, ABI, and optional fingerprint verification are all demonstrated and covered by tests. The read-only probe cannot establish that complete set, so Android CLI mutation remains disabled.

The opt-in `:droidproof-host:probeAndroidCliCompatibility` task requires only the Android CLI path and SDK root, accepts a bounded optional timeout, writes deterministic JSON under the module build directory, and is neither cacheable nor part of `check`.

## Consequences

Operators can diagnose the installed Android CLI without an APK, scenario, provisioning contract, AVD, device, network dependency, or mutation. JVM fakes cover interpretation and safety; a real-host probe only characterizes that specific installation. Windows emulator management is incompatible while the Android CLI documentation states that those commands are disabled there.

The deprecated `avdmanager` and standalone `emulator` implementation remains DroidProof's only functional deterministic owned-provisioning backend until Android CLI exposes and demonstrates the full contract without weakening ADR 0018.
