# ADR 0018: Deterministic owned emulator provisioning

## Decision

DroidProof supports an opt-in third target mode selected by `droidproof.provisioningPath`.  A strict schema-v1 provisioning contract identifies the exact system-image package and revision, API, ABI, emulator, platform-tools (when requested), explicitly versioned command-line-tools directory, device profile, and optional boot fingerprint. Host SDK paths are supplied separately.

The current backend is intentionally the legacy/deprecated SDK backend: it invokes `avdmanager` and the standalone `emulator` interface. It verifies `source.properties`, creates an AVD and isolated `ANDROID_AVD_HOME` only below `droidproof.provisioningStateRoot`, marks it with a bounded ownership record, starts it with `-wipe-data -no-snapshot` and an explicit even port, verifies boot/API/ABI/fingerprint, then stops the launched process before deleting only the strictly validated marked owned directory. It never accepts licenses, updates SDK packages, kills ADB, or uses a shell. Existing `deviceSerial` and `avdName` modes retain their ownership boundaries.

## Consequences

This establishes deterministic selection and clean initialization of the requested baseline, not remote attestation. External host/ADB actors, hypervisor and scheduler behavior, wall clock, package repository retention, and bit-for-bit Android execution remain outside the proof boundary.

Android CLI migration remains required compatibility work, not a decision made by this ADR. It needs demonstrated parity for exact SDK/image selection, owned storage, deterministic serial/port association, clean startup, bounded shutdown, and supported hosts.
