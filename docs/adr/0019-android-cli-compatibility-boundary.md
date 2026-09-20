# ADR 0019: Android CLI compatibility boundary

## Decision

Keep `legacy` as the default managed-emulator backend. It retains the existing `avdmanager` and standalone `emulator` implementation and its ownership checks. Add explicit `android-cli` selection and an explicit executable path, but make it fail closed after bounded version discovery until the installed CLI has demonstrated exact image/revision selection, isolated DroidProof-owned storage, clean-state creation, deterministic serial association, bounded stop, and safe removal.

There is no automatic backend fallback. External `deviceSerial` remains externally owned and does not require Android CLI.

## Consequences

The runner now has a named, validated `--key=value` configuration boundary, so Gradle wiring cannot silently shift provisioning paths, SDK roots, or the project version. Android CLI status is intentionally partial: JVM tests verify parsing and command construction, while local integration is unavailable until an installed CLI and already-installed compatible image can be inspected safely.
