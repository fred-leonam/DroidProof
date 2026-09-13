# ADR 0005: Ordered UI steps and a bounded tap

## Status

Accepted.

## Decision

Scenario schema v1 retains its existing document shape and exact assertion semantics. Schema v2 has separate strict Kotlin serialization and a sealed `ScenarioStep` list discriminated by `type`. Both versions expose an ordered view to the existing coordinator. Version dispatch examines only the envelope version; typed serializers reject unknown fields and step types. The loader reads at most 1 MiB plus one rejection byte, validates UTF-8, and hashes and publishes the exact accepted bytes.

V2 requires 1–100 steps and a final `assertUiNode`. `tapUiNode` takes only a fully qualified `resourceId`. `assertUiNode` takes `resourceId`, nonempty exact `text` (up to 1024 characters), `deadlineMillis` (1–300000), and `pollIntervalMillis` (1 through the deadline). Every ID belongs to `expectedPackage`. The existing package and fully qualified launch-component constraints apply to both versions. There are no scenario-supplied coordinates, shell commands or additional selector forms.

The coordinator retains PREFLIGHT → ARTIFACT_BINDING → LAUNCH → ASSERTION → CAPTURE → FINALIZATION. Ordered steps execute inside ASSERTION. A tap collects one fresh hierarchy through the existing bounded ADB operation and uses the same hardened SAX parser as assertions. It requires exactly one node matching package and resource ID, with nonnegative Int coordinates and positive-area `[left,top][right,bottom]` bounds. Integer centers round down and cannot overflow. A typed `tap` operation sends a validated serial and numeric argument-list elements to `input tap`, with bounded timeout and output. A tap is never retried.

A successful command acknowledges input dispatch, not delivery to a particular view or completion of application behavior. The following exact assertion supplies the behavioral observation. A node may move or become covered between collection and dispatch; this milestone does not establish atomic selection and interaction, pixel visibility, or screen-size validation. No activity state is fabricated.

## Results and evidence

Execution result document schema 2 adds one-based step indices, explicit types, start/end host timestamps, outcomes, bounded implementation-authored error reasons and bundle-relative hierarchy references. Assertion steps include the existing detailed assertion document. The top-level assertion is the last attempted assertion, or a NOT_EVALUATED description of the final expected assertion when none ran. Skipped steps use the time of the skip decision for both timestamps; those are not execution observations.

Each valid retained step hierarchy goes through `EvidenceBundleWriter`, including valid XML that could not resolve a tap. Unsafe/malformed XML and failed collection output are not published. V2 uses `ui/steps/001-tap-before.xml`, `ui/steps/002-assert.xml`, etc. V1 retains `ui/hierarchy.xml`. The existing schema-v3 inventory binds sizes and SHA-256 values. Step timeline events (`scenario.step.tap`, `scenario.step.assert`) reference these files, so the existing verifier checks the references without a new evidence schema. IDs encode lifecycle position and step index for stable ordering, including equal timestamps. Existing wall-clock sorting remains unchanged.

An observed assertion nonmatch stops later steps and yields COMPLETED/FAILED. Tap collection, resolution or command errors stop all later device work and yield ERROR/NOT_EVALUATED. Cancellation and overall deadline checks run before each step and device operation and after each step. Already dispatched input cannot be undone. Remaining steps are explicitly SKIPPED. The overall budget includes assertion deadlines and bounded per-step device work, capped at one hour. Screenshot capture and final installed-APK identity verification remain after successfully evaluated step execution, including a behavioral failure. A missing screenshot remains partial evidence; a lost final artifact identity invalidates the scenario evaluation.

The sample uses local View listeners to update its status. Activity creation and a new launch intent establish the initial sample state. Both v1 samples remain; the canonical v2 pair now enters a bounded name, taps the action, and either expects the resulting greeting or intentionally expects incorrect final text. The JVM suite uses synthetic hierarchies and fake device operations and requires no emulator.
