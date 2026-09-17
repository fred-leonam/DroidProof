# ADR 0017: Bounded owned emulator lifecycle

## Status

Accepted.

## Decision

DroidProof has two exclusive target modes. `deviceSerial` uses an externally owned, already-running Android instance and never starts or stops it. `avdName` validates one existing AVD, starts it with an explicitly validated even console port, and derives the exact `emulator-<port>` serial. The legacy SDK emulator executable is isolated behind `EmulatorLifecycleManager`.

Managed startup is bounded and fails closed: the AVD must be listed before launch; the child process must remain alive; the exact serial must be ADB-visible in `device` state; and `sys.boot_completed` must equal `1`. The normal SmokeAdb preflight and cooperative serial lease then remain unchanged. No AVD or SDK provisioning is performed.

The managed session is closed outside `SmokeCoordinator`, after its network cleanup, environment restoration/recovery finalization, and publication paths have completed. Shutdown uses `adb -s <owned serial> emu kill`, bounded waiting, and force termination only of the exact child process owned by the session. Cleanup failures are suppressed onto an earlier execution failure.

## Non-goals and proof boundary

This is bounded host lifecycle ownership, not deterministic provisioning, image or snapshot provenance, attestation, exclusive control of an emulator, or evidence of emulator lifecycle facts. It does not install SDK packages, create/wipe/delete AVDs, restart ADB, or guess among running devices.
