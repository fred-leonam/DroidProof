# ADR 0015: Crash-resilient emulator environment recovery journal

## Status

Accepted.

## Decision

`APPLY_AND_RESTORE` writes a strict recovery-journal schema v1 outside the evidence bundle. It contains only a transaction ID, validated serial, initial capability observation (API, fingerprint, boot ID), original low-level environment state, bounded timestamps/detail, and a phase. The file name is the SHA-256 digest of the validated serial and the state root is configured by `droidproof.recoveryStateRoot`.

The journal transitions are validated: `PREPARED` -> `MUTATION_STARTED` -> `APPLIED_VERIFIED` -> `RESTORATION_STARTED` -> `RESTORED_VERIFIED`. Any non-terminal phase may become `RECOVERY_REQUIRED`; rollback can begin from a captured or mutation-started state when later verification failed. The journal is durably written before the first environment write, through a temporary regular file, forced where supported, and atomically replaced where supported. Unknown fields, unsupported versions, malformed UTF-8, symbolic links, non-regular files, and oversized documents fail closed.

An ordinary smoke run never restores an unresolved journal. It stops after read-only preflight and capability observation and directs the operator to `recoverEmulatorEnvironment`. That task requires an explicit serial and shares the serial lease, ADB path handling, preflight, and bounded operations. It restores only if API level, build fingerprint, and boot ID all equal the initial journal observation. Drift or unavailable identity refuses automatic restoration and retains the journal with bounded manual guidance containing the saved original values.

## Limits

This improves recovery after host interruption but cannot make the SIGKILL timing window disappear, guarantee filesystem durability across every platform or power failure, prevent external ADB actors, or safely restore after identity/boot drift. It is not emulator provisioning, lifecycle management, exclusive ownership, continuous monitoring, image provenance, or remote attestation.
