# ADR 0017: Bounded owned emulator lifecycle

## Status

Accepted.

## Decision

DroidProof has two exclusive target modes. `deviceSerial` uses an externally owned, already-running Android instance and never starts or stops it. `avdName` validates one existing AVD, starts it with an explicitly validated even console port, and derives the exact `emulator-<port>` serial. The legacy SDK emulator executable is isolated behind `EmulatorLifecycleManager`.

Managed startup is bounded and fails closed: the AVD must be listed before launch; the child process must remain alive; the exact serial must be ADB-visible in `device` state; and `sys.boot_completed` must equal `1`. The normal SmokeAdb preflight and cooperative serial lease then remain unchanged. No AVD or SDK provisioning is performed.

The cooperative serial lease is acquired before managed provisioning or startup and remains held through scenario execution, environment restoration/recovery finalization, owned-process shutdown, and permitted owned-state removal. A coordinator given that outer lease does not reacquire it; direct coordinator callers still acquire before their first device operation. The lease remains cooperative same-host DroidProof coordination, not ownership or exclusion of arbitrary ADB clients.

An existing caller-selected AVD is never wiped. Only an isolated, positively marked DroidProof-owned provisioning directory receives the clean-baseline `-wipe-data -no-snapshot` launch arguments. Shutdown uses `adb -s <owned serial> emu kill` only as a graceful request, then bounded termination/escalation of the exact child process. Removal is permitted only after that child exit is positively confirmed; uncertain shutdown retains the directory and marker and makes the operation unsuccessful. Cleanup failures are suppressed onto an earlier execution failure.

## Non-goals and proof boundary

This is bounded host lifecycle ownership, not deterministic provisioning, image or snapshot provenance, attestation, exclusive control of an emulator, or evidence of emulator lifecycle facts. It does not install SDK packages, create/wipe/delete AVDs, restart ADB, or guess among running devices.
