# ADR 0011: Verify-only emulator environment contract

## Status

Accepted.

## Context and proof boundary

Artifact binding proves which APK bytes were installed, but the same application can behave differently under a different locale, orientation, or Android animation configuration. This milestone therefore accepts an optional, independent environment contract and verifies the already-selected emulator before installation, launch, UI actions, or network setup.

An environment mismatch is an execution precondition failure, not an application behavior. It produces execution status `ERROR`, scenario verdict `NOT_EVALUATED`, and partial evidence when accepted inputs permit a truthful schema-v3 bundle. A mismatch must never become `FAILED`, because the scenario did not run under its requested conditions.

This slice observes but does not mutate. DroidProof does not write settings, lock rotation, change locale, disable animations, restore prior state, or start, stop, create, delete, or provision an emulator. Mutation and restoration require a later design with ownership, rollback, interruption, and concurrent-use semantics.

## Contract and parsing

`droidproof.environmentPath` names an optional strict JSON contract. Version 1 contains a canonical BCP-47 locale, `PORTRAIT` or `LANDSCAPE`, and finite non-negative window, transition, and animator animation scales. The host accepts only a regular non-symbolic-link UTF-8 file of at most 64 KiB, rejects malformed UTF-8, unknown fields, unsupported versions and values, and preserves the exact accepted bytes and their SHA-256.

The contract is independent of scenario schemas v1–v4. Those schemas and evidence-manifest schema v3 remain unchanged. Runs without the property perform no new environment ADB calls, record a skipped `ENVIRONMENT` stage with “not requested,” and retain explicitly unavailable manifest observations.

## Supported Android observations

Every command is an argument list executed with the validated, explicit serial and bounded output/time. Preflight continues to require an emulator and primary user 0. Version 1 supports exactly:

- locale: `adb -s SERIAL shell getprop persist.sys.locale`;
- auto-rotation state: `adb -s SERIAL shell settings --user 0 get system accelerometer_rotation`;
- configured rotation: `adb -s SERIAL shell settings --user 0 get system user_rotation`;
- animation scales: `adb -s SERIAL shell settings get global window_animation_scale`, `transition_animation_scale`, and `animator_duration_scale`.

Locale output must be one short printable line that parses to a non-`und` BCP-47 tag; underscores are normalized to hyphens for observation. Orientation is available only when `accelerometer_rotation` is exactly `0`. `user_rotation` 0 or 2 maps to portrait and 1 or 3 maps to landscape. This proves the locked/configured orientation class, not physical display posture. Auto-rotation, missing or `null` settings, extra lines, stderr, negative or non-finite scales, unexpected values, output-limit failures, and parse failures are unavailable or unsupported rather than guessed. Raw stderr, host paths, and uncontrolled device output are not serialized.

These commands reflect Android interfaces commonly available on the supported emulator slice, not a compatibility promise for every API level, vendor build, multi-user configuration, foldable posture, external display, or application-specific locale. Values are sequential point observations and are not atomic or continuously attested.

## Evaluation, failure, cancellation, and timeout

`ENVIRONMENT` follows `PREFLIGHT` and precedes `ARTIFACT_BINDING`. The strict evaluation document uses `MATCHED`, `MISMATCHED`, or `UNAVAILABLE` per field and overall. Only `MATCHED` permits later work. Mismatch and unavailable observations are `ERROR`/`NOT_EVALUATED`; cancellation remains `CANCELLED`, and bounded command or overall deadline expiry remains an execution error rather than a mismatch. Later install, reverse/network setup, launch, UI, and capture work is skipped.

Complete evidence for a requested contract requires a matched evaluation plus the existing scenario, UI, capture, artifact-binding, and applicable network requirements. A mismatch normally retains an integrity-valid partial bundle containing the accepted contract and evaluation. A cancellation before a complete evaluation may retain the contract without inventing an evaluation.

## Evidence, integrity, authentication, and reporting

Exact input bytes are inventoried as `environment/contract.json`; the strict evaluation schema v1 is inventoried as `environment/evaluation.json`. The environment-stage host timeline event references the evaluation. Schema-v3 `observedEnvironment` uses validated normalized values from that evaluation. Random seed and controlled clock remain explicitly unavailable.

Evidence-manifest schema v3, authentication-envelope schema v1, and scenario schemas v1–v4 are unchanged. Normal inventory digest/size verification detects environment-evidence tampering. Optional Ed25519 authentication signs the exact manifest/timeline core; the signed manifest transitively authenticates the environment inventory when bundle integrity also succeeds. No key or trust material enters evidence.

The HTML report renders requested and observed values only after full bundle verification and only from the inventoried evaluation. Integrity failure or explicitly requested authentication failure remains diagnostic-only. Normalized bounded values are rendered; raw device output is not.

## Consequences and limitations

This contract makes a narrow set of environmental prerequisites explicit and reviewable. It does not make the emulator deterministic. It does not cover image provenance, API/system image, hardware profile, timezone, clock, random seeds, networking outside the mock server, sensors, permissions, accessibility state, font scale, density, battery, thermal state, background processes, snapshots, boot state, or host/emulator version. It is verification of three point-in-time settings, not full deterministic emulator provisioning or remote attestation.
