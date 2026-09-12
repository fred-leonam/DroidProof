# ADR 0004: Artifact-bound Android smoke execution

## Status

Accepted.

## Context and boundary

DroidProof can already create integrity-checked evidence bundles and collect read-only Android observations. It needs one executable scenario before adding a general scenario language or broader device control.

This milestone accepts one local APK, one strict versioned JSON scenario, one explicit package scope and one explicit serial for an already-authorized test emulator. It installs only when needed, launches one fully qualified activity, and evaluates one package/resource-ID/exact-text match in the accessibility hierarchy. It does not add clicks, deep links, arbitrary shell commands, business-action retries, network simulation, recording, Compose semantics, emulator provisioning, cloud services or reports. Logcat remains disabled.

The verdict establishes only that the specified node was observed in the exposed accessibility hierarchy for the identified APK under the recorded conditions. It does not establish pixel visibility, Compose semantics or general application correctness.

## Decision

`droidproof-host` is a Kotlin/JVM production module over the existing model, evidence and device modules. Fixed typed operations built on `CommandRunner` cover package inspection, APK retrieval and installation, activity launch, and `uiautomator dump`. Every device operation carries the validated serial. Device-returned APK paths and generated remote hierarchy paths are validated and passed as argument-list elements rather than interpolated shell text.

The coordinator uses `PREFLIGHT`, `ARTIFACT_BINDING`, `LAUNCH`, `ASSERTION`, `CAPTURE` and `FINALIZATION` stages. A monotonic overall deadline bounds device work. Assertion polling has its own monotonic deadline and injectable wait abstraction. Cancellation or a fatal prerequisite stops later device actions; bounded local finalization may still preserve available evidence and cancellation remains visible in the execution result.

The input APK is copied to owned staging and that snapshot is both hashed and installed. DroidProof supports one APK in primary user 0. It reuses an equal installed APK, installs an absent package, refuses different bytes by default, and replaces only with explicit `replaceExisting`. It never uninstalls, downgrades, clears data, grants unrelated permissions, re-signs the APK or stores APK bytes in the final bundle. The installed package's single APK is retrieved and hashed before launch and after assertion/capture.

These hashes are discrete equality observations, not continuous attestation against concurrent updates. No signing-certificate fingerprint is fabricated. Hashes bind bundle contents but do not authenticate their producer.

UI hierarchy collection uses a unique owned remote path per attempt, bounded output and cleanup limited to that path. A fresh successful dump and retrieval are required. XML Document Type Definitions and external entities are disabled, and input bytes and node traversal are bounded. A match requires package, fully qualified resource ID and exact text on the same node. The retained hierarchy is the matched observation or the last valid nonmatch at deadline. Screenshot and hierarchy are sequential, not atomic, observations.

## Verdict and failure semantics

Scenario verdict, execution status, evidence completeness and bundle integrity remain separate:

- `PASSED`, `FAILED` and `NOT_EVALUATED` describe the assertion.
- `COMPLETED`, `ERROR` and `CANCELLED` describe execution.
- `COMPLETE` and `PARTIAL` describe required evidence.
- the existing verifier describes bundle integrity.

A valid nonmatching hierarchy through the deadline is `FAILED`. A hierarchy command failure, malformed hierarchy, disconnection or lost final APK identity is `ERROR` with `NOT_EVALUATED`. A matched hierarchy with a missing screenshot remains `PASSED` with `PARTIAL` evidence. The task succeeds only for completed, passed, complete and integrity-valid evidence. A failed assertion may therefore have a valid bundle while the task exits unsuccessfully.

## Schema compatibility

Schema version 3 has a dedicated execution-manifest type. It records the real input APK identity, scenario identity, artifact-binding observations, requested configuration, observed or explicitly unavailable environment values, execution summary, detailed-evidence references and the existing evidence-file inventory.

`ScenarioIdentity.dataSha256` is the SHA-256 of the exact accepted bytes copied to `scenario/scenario.json`. Runtime host paths are not serialized. Requested configuration is separate from observed environment. Unknown locale, orientation, animation scales, random seed and controlled clock remain unavailable with reasons.

Version 1 and version 2 reading remain explicit. The version 2 writer and deterministic synthetic sample remain unchanged. Version-specific validation keeps the version 2 inventory checks and adds version 3 manifest-reference checks without a general migration framework.

The actual DroidProof build version continues to come from the root Gradle project version. It is separate from the sample application's version and every document schema number.

## Finalization and limitations

Successful publication uses the existing bounded ingestion, path validation, deterministic JSON, staging, backup-and-rename writer and verifier. Supporting references are included only when their files exist. Failures after accepted scenario and APK inputs can still produce a truthful partial version 3 bundle. Earlier failures retain `failed-run.json` without an invented manifest. Publication errors are recorded separately and do not replace the original execution error.

Backup-and-rename is rollback for caught failures, not crash-atomic publication. This slice is limited to the tested emulator and API capabilities exposed through ADB and `uiautomator`; it does not claim support for every Android device. Evidence remains local build output and is not uploaded by continuous integration.
