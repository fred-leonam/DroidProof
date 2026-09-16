# ADR 0014: Emulator capability and continuity observation

## Status

Accepted.

## Problem

An environment transaction previously had no integrity-bound observation of the selected emulator image and boot before mutation, and no explicit check for drift before rollback.

## Decision

Under the cooperative serial lease, after emulator/user preflight and before any mutation, DroidProof reads only `ro.build.version.sdk`, `ro.build.fingerprint`, and `ro.boot.boot_id`, plus records the narrow command surfaces used by this slice. Values are bounded safe lines; API is numeric and boot ID is UUID-compatible. The probe uses serial-scoped ADB argument lists only.

For a requested environment contract, after UI/capture/network evaluation and before restoration, DroidProof repeats identity observation and evaluates the same contract. Image or boot change, environment drift, or unavailable observations produce `MISMATCHED` or `UNAVAILABLE`, an `ERROR`/`NOT_EVALUATED` partial result, and continuity evidence. Once mutation starts, rollback and rollback verification are still attempted with their separate bounds.

`environment/capabilities.json` and `environment/continuity.json` are ordinary schema-v1 documents inventoried by the schema-v3 manifest and linked from the canonical timeline, so existing integrity verification and optional signatures bind them.

## Scope and limits

These are capability and sequential continuity observations, not provisioning, exclusive ownership, image provenance, remote attestation, continuous monitoring, or proof of future write permission. The cooperative lease cannot stop Android Studio, people, arbitrary ADB processes, another host, crashes, SIGKILL, power loss, or emulator loss. Sequential observations do not prove uninterrupted stability.
