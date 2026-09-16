# ADR 0013: Cooperative emulator execution lease

## Status

Accepted.

## Problem

Two DroidProof host processes can otherwise issue conflicting ADB operations to the same selected emulator, including an `APPLY_AND_RESTORE` transaction.

## Decision

Before preflight and every device-affecting operation, DroidProof acquires a non-blocking OS file lock keyed by a SHA-256-derived selected serial in a deterministic temporary-file namespace. The lock is held through network reverse cleanup, environment restoration, and restoration verification, then released deterministically on normal completion, failure, and cooperative cancellation.

If acquisition fails, the run reports a preflight error and issues no device operation. Lock-file presence does not imply ownership; the operating-system lock does.

## Scope and limits

This is cooperative same-host DroidProof-process exclusion, not exclusive ownership of an emulator. It does not prevent Android Studio, humans, arbitrary `adb` commands, non-cooperating or malicious processes, another host, or users outside the lock namespace from mutating the emulator. It cannot guarantee cleanup through host/emulator crashes, power loss, or SIGKILL, and it is not remote attestation.
