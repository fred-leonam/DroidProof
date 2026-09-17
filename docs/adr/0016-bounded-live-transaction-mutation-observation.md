# ADR 0016: Bounded live-transaction mutation observation

## Status

Accepted.

## Threat and problem

The cooperative serial lease coordinates DroidProof processes on one host but cannot prevent Android Studio, people, other Android Debug Bridge processes, another host, or the emulator itself from changing observable transaction state. A final-only check can detect some late differences but cannot stop later scenario actions after an earlier observed change.

## Decision

DroidProof records deterministic point observations after artifact binding, after launch, after each completed ordered scenario step, immediately before final capture, and after capture before finalization. There is no polling loop, background observer, or unbounded monitoring.

Every reached checkpoint compares the selected emulator API level, build fingerprint, and boot identifier with the initial capability observation. When an environment contract is present, it evaluates the same requested locale, locked user-0 orientation, and three animation scales. Once the target package is bound, it compares the installed APK bytes with the staged input APK.

`environment/transaction-continuity.json` records the available initial baselines, each checkpoint and optional step index, component outcomes, the aggregate outcome, bounded safe details, and explicit limitations. Outcomes are `MATCHED`, `DRIFT_DETECTED`, `UNAVAILABLE`, and `NOT_EVALUATED`. The schema-v3 manifest inventories the document and the canonical timeline links it.

## Failure and cleanup semantics

`DRIFT_DETECTED` and `UNAVAILABLE` stop further scenario actions and produce an `ERROR`/`NOT_EVALUATED` partial result. The first failure remains the primary diagnostic. An already-started owned network session is still inspected and cleaned up. Existing pre-restoration continuity observation, `APPLY_AND_RESTORE` rollback, rollback verification, recovery-journal transitions, evidence finalization, and bundle verification still run within their existing bounds.

## Proof boundary and non-goals

The evidence proves only that sequential bounded observations at recorded checkpoints matched, detected drift, were unavailable, or were not evaluated. A match does not prove stability between checkpoints, and fields outside the narrow identity, requested environment, and target APK observations are not covered.

This feature is not continuous monitoring, exclusive emulator ownership, actor attribution, emulator provisioning, image provenance, remote attestation, or proof that all external mutation is detectable. It does not identify who or what caused an observed change.
