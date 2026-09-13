# ADR 0006: Bounded text entry into an exact UI node

## Status

Accepted.

## Decision

Schema v2 adds the sealed, strictly serialized step `typeTextUiNode`, with only `resourceId` and `text`. Schema v1 and existing v2 documents retain their shapes and behavior. Input must contain 1–128 characters from ASCII A–Z, a–z, 0–9, period, underscore, hyphen and at-sign. Empty values, whitespace, controls, Unicode and shell metacharacters are rejected during scenario loading and again by the typed ADB operation. These are evidence-visible, non-secret test identifiers. The exact scenario bytes, including input text, are preserved and hashed. This is not secret-safe input.

An exhaustive sealed-step mapping provides StepType; each enum entry defines its timeline type, hierarchy suffix and bounded device-operation budget. Execution also uses an exhaustive sealed-step when. New steps cannot default to assertion semantics.

Tap and text entry share one coordinator branch for fresh bounded hierarchy acquisition and the existing UiHierarchyParser.inspectTap implementation. Both require exactly one node with the expected package and fully qualified resource ID, valid nonnegative positive-area Int bounds, and the same overflow-safe integer center. No second parser or selector language is introduced.

Text entry executes one hierarchy dump, safe parse and resolution, retention of accepted XML, one focus-establishing tap, then one typed inputText operation. Cancellation and the overall deadline are checked before both mutations and after the step. Neither mutation is retried. A failed resolution prevents both inputs; a failed focus tap prevents text dispatch; text failure prevents all subsequent device work. Infrastructure failures produce ERROR/NOT_EVALUATED; cancellation produces CANCELLED/NOT_EVALUATED. Actual assertion matches or deadline nonmatches continue to determine PASSED or FAILED on completed execution.

The real adapter invokes the configured ADB executable through CommandRunner's argument list: `-s SERIAL shell input text VALUE`. It launches no host shell and performs no escaping or scenario-supplied command dispatch. The restricted value requires no shell quoting. Timeout and output remain bounded. Failure kinds are preserved and raw stdout/stderr are replaced with implementation-authored reasons.

## Evidence

The accepted input hierarchy uses `ui/steps/001-input-before.xml` (index varies), passes through EvidenceBundleWriter and receives the existing schema-v3 SHA-256 and byte-size inventory binding. StepOutcome and `scenario.step.type_text` reference it. Timeline attributes contain only step index and status, never input text. Safely parsed unresolved hierarchies may be retained; unsafe, oversized or failed collection output is not published. Screenshot capture and final APK identity comparison retain their existing pipeline and verifier.

## Sample and limitations

The Android View sample adds an initially empty name field. Its action displays `Hello <name>` for a nonempty field and preserves the original action-completed text for an empty field. A new launch intent clears the sample field and resets status. Passing and intentionally wrong-assertion text scenarios accompany the unchanged v1 and tap-only scenarios.

A successful input command acknowledges dispatch, not focus or text delivery. Hierarchy selection, tapping, typing, assertion and screenshot capture are sequential observations; a concurrent UI change can alter the recipient. Only the following assertion supplies behavioral evidence. Existing field content is not cleared or replaced by DroidProof. Arbitrary Unicode, whitespace, secrets, IME control, keyboard hiding, key events and general automation remain out of scope.
