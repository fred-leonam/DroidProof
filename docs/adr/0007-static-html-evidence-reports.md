# ADR 0007: Static HTML as a derived evidence view

## Status

Accepted.

## Context and boundary

DroidProof evidence bundles are structured source artifacts. Schema versions 2 and 3 bind registered evidence files to the manifest by relative path, byte size and SHA-256; schema version 1 predates that inventory. The verifier rejects unexpected bundle files. Putting a generated report in a bundle would both invalidate an already published bundle and blur the distinction between checked evidence and a presentation of it.

The reporting slice must remain usable on a JDK-only host without Android SDK classes, ADB, an emulator, a browser framework, or execution internals. Bundle fields and evidence filenames are untrusted input even after their consistency has been checked.

## Decision

`droidproof-report` is a pure Kotlin/JVM 17 module depending only on `droidproof-model`, `droidproof-evidence`, and Kotlin serialization JSON. `EvidenceReportGenerator` invokes the authoritative `EvidenceBundleVerifier` before parsing content for presentation and uses the shared evidence JSON configuration and public schema models. It supports the verifier's schema-v1, schema-v2, and schema-v3 compatibility policy without duplicating verification logic.

An evidence bundle is the integrity-checked source artifact. A generated HTML report is a disposable, derived representation and is never evidence itself. Reports must be written outside their source bundle; they are not added to `manifest.evidenceFiles`, are not covered by bundle verification, and do not mutate the source. The report states that SHA-256 consistency does not authenticate the producer.

Valid schema-v3 reports present the stored execution status, verdict, completeness, artifact binding, requested configuration, observed or explicitly unavailable environment fields, canonical timeline, verified inventory, and local links. Screenshot previews use the existing verified bundle file. Other evidence is linked but its content is not interpreted as HTML. Schema-v2 reports present their manifest environment contract and timeline. Schema-v1 reports are explicitly marked legacy/limited, preserve the verifier warning that evidence-file integrity is unavailable, and provide no evidence links or previews.

The output is one deterministic UTF-8 HTML document with inline CSS and no JavaScript, remote resources, fonts, network requests, or copied evidence. Links are relative from the report to paths admitted by the verified inventory. Displayed paths remain bundle-relative. One centralized HTML encoder escapes ampersand, angle brackets, both quote characters, and every other bundle-derived string is routed through it before insertion into text or attributes.

If verification reports errors, the generator writes only a limited diagnostic page with the safely escaped schema version and structured issues. It does not parse or render artifact, scenario, timeline, preview, or link claims from the invalid bundle. The explicit Gradle task then exits unsuccessfully even though the diagnostic page was preserved.

## Gradle and output policy

`generateEvidenceReport` requires `droidproof.bundlePath` and accepts optional `droidproof.reportPath`. Its default output is `droidproof-report/build/reports/droidproof/evidence-report.html`. It is explicit, not attached to `check`, never considered up-to-date, and never restored from the build cache. External evidence is therefore not modeled as cacheable build input. Provider-backed command arguments retain configuration-cache compatibility without reading or validating the bundle during configuration.

## Consequences and limitations

The report is portable with its linked bundle only while their relative filesystem relationship is preserved. It does not embed evidence, authenticate the bundle producer, validate application behavior beyond the stored execution model, add signing, provide a JavaScript UI, host reports, or interpret arbitrary evidence documents. Opening linked XML or JSON remains a local browser/viewer action. Regenerating a report from unchanged evidence at the same destination produces byte-identical HTML.
