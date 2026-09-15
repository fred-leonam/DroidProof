# DroidProof

**Artifact-bound, evidence-oriented verification for Android applications.**

DroidProof is an early Kotlin/JVM prototype. Its current vertical slice installs an exact APK on an explicitly selected emulator, enters a known UI value, runs the sample application's real HTTP retry against a controlled local backend, verifies the server-observed request contract, captures UI and network observations, publishes an integrity-bound and optionally Ed25519-authenticated evidence bundle, verifies it, and renders an offline HTML report.

The APIs and schemas are not a stable release.

## Current implementation

- `droidproof-model` owns validated evidence identities, portable bundle paths, evidence descriptors, schema-v1/v2 legacy manifests, the schema-v3 execution manifest, and canonical timeline events.
- `droidproof-evidence` transactionally writes bundles, streams SHA-256 and byte-size inventory data, and verifies schemas v1, v2, and v3 with structured issues. It can optionally sign the exact completed manifest/timeline core with JDK 17 Ed25519 and separately reports integrity and authentication against a caller-supplied public key. Its synthetic sample remains unsigned and receives the authoritative Gradle project version.
- `droidproof-device` provides bounded ADB process execution and explicit screenshot, allowlisted metadata, and opt-in PID-filtered logcat collection.
- `droidproof-mock-server` is a small Android-independent JDK HTTP server. It binds only to `127.0.0.1`, uses an ephemeral host port, serves the narrow deterministic `POST /orders` response plan, performs bounded exact-byte request-contract evaluation, records safe exchange metadata, and exposes explicit start/inspect/stop lifecycle methods.
- `droidproof-host` performs preflight, exact APK byte binding, optional mock-server and serial-scoped ADB reverse setup, activity launch, ordered UI steps, screenshot capture, final APK identity checking, response-sequence and request-contract evaluation, cleanup, optional signing at publication, and verification. Signing keys stay outside Android execution and evidence documents.
- `droidproof-report` verifies a bundle before rendering a deterministic static HTML report. It distinguishes unsigned, signed-without-external-trust, authenticated, and authentication-failure states. Valid network bundles show safe request-contract outcomes, issue codes, request size/SHA-256 metadata, response status, and links to verified exchange files. Integrity or explicitly requested authentication failures get only a limited diagnostic report.
- `samples/smoke-app` preserves the v1/v2 greeting flow and adds a separate real HTTP order action. That action performs network I/O off the main thread, retries exactly once after HTTP 503, accepts the deterministic HTTP 201 JSON order ID, and displays `Order order-42 created`. Its single-task launch resets the demo UI between consecutive scenario runs.

Build and test all JVM modules with JDK 17:

```bash
./gradlew check
```

This root verification does not require an Android SDK, ADB executable, emulator, device, Internet connection, or external server. Mock-server tests use only an owned loopback server.

## Versioned contracts

Scenario schema versions and evidence schema versions are separate contracts.

- Scenario v1 retains its single exact UI assertion.
- Scenario v2 retains the ordered `typeTextUiNode`, `tapUiNode`, and `assertUiNode` actions. Existing checked-in v1/v2 scenarios remain valid and keep their behavior.
- Scenario v3 adds one narrow `backendPlan` for `POST /orders`, a stable device-side port, strict request/response byte limits, and an ordered `responsePlan`. It does not change v2 semantics or provide arbitrary scripting.
- Scenario v4 retains that single controlled endpoint and requires one `expectedRequest` containing a narrowly validated media type and non-empty JSON body. Every planned retry request must match the expected method, path, media type, UTF-8 byte size, and SHA-256. V4 does not reinterpret or add request matching to v3.
- Evidence schema v1 remains readable with a `FILE_INTEGRITY_UNAVAILABLE` warning.
- Evidence schema v2 retains its integrity-bound file inventory and synthetic writer.
- Evidence schema v3 remains the execution-bundle format. Network evidence and authentication do not reinterpret or bump it: `EvidenceFileRole.NETWORK`, `EventSource.MOCK_SERVER`, inventory bindings, and timeline evidence references express network observations, while optional authentication uses the separate strict `authenticity.json` authentication schema v1.

`authenticity.json` is a reserved core file rather than ordinary inventoried evidence. It records Ed25519, the SHA-256-derived key ID of the X.509/SPKI public key, exact byte sizes and SHA-256 values for `manifest.json` and `timeline.json`, and a Base64 signature over a deterministic domain-separated message. The bundle does not contain a public key. Trust is established only when the caller supplies a public key from outside the bundle.

Unknown scenario and evidence fields fail parsing. Scenario documents are bounded to 1 MiB and preserved byte-for-byte with their SHA-256. UI selectors remain package-qualified; input text remains restricted to 1–128 non-secret ASCII identifier characters. The v4 expected body must fit the configured request bound and be valid JSON, but matching is deliberately byte-exact rather than semantic JSON equivalence.

V4 accepts `application/json` with either no parameters or exactly one unquoted `charset=utf-8` parameter. Type, subtype, parameter name, and UTF-8 token are case-normalized; optional separator whitespace is normalized. Parameter presence remains significant, so `application/json` does not match `application/json; charset=utf-8`. Other parameters, charsets, quoted values, control characters, and malformed values are rejected.

## Synthetic evidence without Android

```bash
./gradlew :droidproof-evidence:generateSampleEvidence
```

This writes and verifies `droidproof-evidence/build/droidproof-samples/proof-checkout-offline-retry/`. It is a schema-v2 API sample; its network document is synthetic and is distinct from the real mock-server observations produced by scenario v3/v4.

## Standalone device capture

Use an already-authorized device or emulator and installed Android SDK Platform Tools:

```bash
./gradlew :droidproof-device:captureDeviceEvidence \
  -Pdroidproof.deviceSerial=emulator-5554
```

ADB resolution order is an explicit `droidproof.adbPath`, `ANDROID_HOME/platform-tools`, legacy `ANDROID_SDK_ROOT/platform-tools`, then `PATH`. DroidProof does not install SDK tools, accept licenses, authorize devices, restart the shared ADB server, or bypass secure windows. The explicit capture task is not part of `check`.

Optional PID-filtered logcat remains opt-in:

```bash
./gradlew :droidproof-device:captureDeviceEvidence \
  -Pdroidproof.deviceSerial=emulator-5554 \
  -Pdroidproof.includeLogcat=true \
  -Pdroidproof.pid=12345
```

See [ADR 0003](docs/adr/0003-android-device-capture.md) for output, timeout, privacy, and attribution limits.

## Real HTTP request-contract demonstration

The live task requires one local APK, the exact serial of an already-authorized emulator in primary user 0, and installed ADB. It does not create or boot an emulator.

Build the standalone non-debuggable sample release from the repository root. Android Gradle Plugin 8.8.2 uses Gradle 8.10.2, JDK 17, and Android compile SDK 35. Generate a disposable local key outside the repository and point `ANDROID_HOME` at the installed SDK:

```bash
keytool -genkeypair -keystore /tmp/droidproof-smoke-keystore.jks \
  -storepass droidproof -keypass droidproof -alias droidproof-smoke \
  -keyalg RSA -keysize 2048 -validity 3650 \
  -dname 'CN=DroidProof Local Sample'

DROIDPROOF_SAMPLE_STORE_PASSWORD=droidproof \
DROIDPROOF_SAMPLE_KEY_PASSWORD=droidproof \
ANDROID_HOME=/absolute/path/to/Android/Sdk \
./gradlew -p samples/smoke-app assembleRelease \
  -Pdroidproof.sample.keystore=/tmp/droidproof-smoke-keystore.jks \
  -Pdroidproof.sample.keyAlias=droidproof-smoke
```

Confirm the emulator serial, then run the checked-in passing scenario-v4 demonstration. The APK and scenario properties must be absolute paths; `$PWD` provides those when the command is run from the repository root:

```bash
./gradlew :droidproof-host:runSmokeScenario \
  -Pdroidproof.apkPath="$PWD/samples/smoke-app/build/outputs/apk/release/DroidProofSmokeApp-release.apk" \
  -Pdroidproof.scenarioPath="$PWD/samples/smoke-app/scenarios/network-request-passing.json" \
  -Pdroidproof.deviceSerial=emulator-5554 \
  -Pdroidproof.adbPath=/absolute/path/to/Android/Sdk/platform-tools/adb \
  -Pdroidproof.replaceExisting=true
```

The command above produces an unsigned bundle. To create a signed bundle, first generate a disposable Ed25519 PKCS#8 private key and X.509/SPKI public key outside the repository. OpenSSL's PEM output is accepted directly:

```bash
DROIDPROOF_KEY_DIR="$(mktemp -d /tmp/droidproof-ed25519.XXXXXX)"
chmod 700 "$DROIDPROOF_KEY_DIR"
openssl genpkey -algorithm ED25519 -out "$DROIDPROOF_KEY_DIR/private-key.pem"
chmod 600 "$DROIDPROOF_KEY_DIR/private-key.pem"
openssl pkey -in "$DROIDPROOF_KEY_DIR/private-key.pem" -pubout \
  -out "$DROIDPROOF_KEY_DIR/public-key.pem"
printf 'Disposable keys created in %s\n' "$DROIDPROOF_KEY_DIR"
```

Then add both signing properties to `runSmokeScenario` (both or neither are required):

```bash
./gradlew :droidproof-host:runSmokeScenario \
  -Pdroidproof.apkPath="$PWD/samples/smoke-app/build/outputs/apk/release/DroidProofSmokeApp-release.apk" \
  -Pdroidproof.scenarioPath="$PWD/samples/smoke-app/scenarios/network-request-passing.json" \
  -Pdroidproof.deviceSerial=emulator-5554 \
  -Pdroidproof.adbPath=/absolute/path/to/Android/Sdk/platform-tools/adb \
  -Pdroidproof.signingPrivateKeyPath="$DROIDPROOF_KEY_DIR/private-key.pem" \
  -Pdroidproof.signingPublicKeyPath="$DROIDPROOF_KEY_DIR/public-key.pem"
```

Do not place or commit the private key in this repository. The key paths and private key are not written into the bundle, timeline, execution result, or report. Remove the disposable key directory when it is no longer needed.

The scenario explicitly repeats the non-secret literal `DroidProof42` in the `typeTextUiNode` step and the expected body `{"customer":"DroidProof42"}`. There is no variable interpolation or dynamic UI-to-request binding. During execution DroidProof:

1. starts `droidproof-mock-server` on an ephemeral `127.0.0.1` host port;
2. creates `adb -s <serial> reverse tcp:38637 tcp:<host-port>`;
3. receives the application's first real `POST /orders` bytes, verifies its media type and exact bounded body contract, and returns HTTP 503;
4. receives the retry, verifies the same request contract again, and returns HTTP 201 with `{"orderId":"order-42"}`;
5. asserts the final UI text `Order order-42 created` and evaluates the ordered server exchange sequence;
6. removes only the owned reverse mapping and stops the owned server; and
7. publishes and verifies the evidence bundle.

There is no fallback to LAN or uncontrolled networking if setup fails. Reverse removal and server shutdown are attempted after success, assertion failure, host failure, timeout, and cancellation. Cleanup errors do not replace the original failure.

The task writes a fresh directory below `droidproof-host/build/droidproof-runs/` and prints its location. A successful network run has a layout equivalent to:

```text
bundle/
├── authenticity.json                 # optional; present only for a signed bundle
├── manifest.json
├── timeline.json
├── scenario/scenario.json
├── execution/
│   ├── artifact-binding.json
│   └── result.json
├── ui/steps/
│   ├── 001-input-before.xml
│   ├── 002-tap-before.xml
│   └── 003-assert.xml
├── capture/capture.json
├── screenshots/display.png
└── network/exchanges/
    ├── 001.json
    └── 002.json
```

Each network exchange document comes from the actual controlled server observation and contains a host observation timestamp, server sequence, bounded method/path, bounded request and response body metadata, response status, response-plan match metadata, and a request-contract outcome of `MATCHED`, `MISMATCHED`, or `NOT_EVALUATED`. Deterministic issue codes distinguish wrong method/path/media type, body size/hash differences, incomplete reads, and limit excess. Request and response bodies are not copied into exchange documents; the configured expected request and responses remain in the exact scenario evidence. Every network file is inventoried with role `network`, byte size, and SHA-256, and every server event links to its file from the canonical timeline.

Success requires completed execution, matched UI assertion, two matched request contracts, matched ordered response plan and exchange count, matching APK bytes before and after, complete required evidence, and successful bundle verification. A complete request mismatch is a behavioral failure: execution remains `COMPLETED`, the verdict is `FAILED`, and the integrity-valid bundle is retained. Unavailable/incomplete request collection, other infrastructure failure, or cancellation yields `NOT_EVALUATED`, rather than a fabricated request match or mismatch.

Run the intentional v4 failure with the same APK and `network-request-failing.json`. It keeps the UI input and application behavior unchanged but expects `{"customer":"WrongCustomer"}`. The app still reaches `Order order-42 created`; both observed requests are reported as mismatches, the execution is `COMPLETED`, the scenario verdict is `FAILED`, and the Gradle task exits nonzero because the success criteria were deliberately not met:

```bash
./gradlew :droidproof-host:runSmokeScenario \
  -Pdroidproof.apkPath="$PWD/samples/smoke-app/build/outputs/apk/release/DroidProofSmokeApp-release.apk" \
  -Pdroidproof.scenarioPath="$PWD/samples/smoke-app/scenarios/network-request-failing.json" \
  -Pdroidproof.deviceSerial=emulator-5554 \
  -Pdroidproof.adbPath=/absolute/path/to/Android/Sdk/platform-tools/adb
```

The v1 `passing.json`/`failing.json`, v2 `interactive-passing.json`/`interactive-failing.json`, and v3 `network-passing.json` demonstrations remain unchanged and supported. Add `-Pdroidproof.replaceExisting=true` only when intentionally replacing different installed bytes for the target package.

## Offline HTML report

Generate a report from a preserved bundle, using paths outside that bundle:

```bash
./gradlew :droidproof-report:generateEvidenceReport \
  -Pdroidproof.bundlePath=/absolute/path/to/bundle

./gradlew :droidproof-report:generateEvidenceReport \
  -Pdroidproof.bundlePath=/absolute/path/to/bundle \
  -Pdroidproof.reportPath=/absolute/path/to/report.html
```

The default output is `droidproof-report/build/reports/droidproof/evidence-report.html`. The report has inline CSS, no JavaScript, and no external resources. It shows verified execution, artifact, environment, timeline, screenshot, inventory, and network metadata. V4 exchange rows show request-contract outcome and issue codes, observed request byte size and SHA-256, and response status. Network bodies are not injected into HTML; exchange rows link to verified local evidence files. If any registered network or other evidence is missing or tampered, verification fails and the report omits all unverified artifact, scenario, timeline, network, and preview content.

Without an external key, the report says either that the bundle is unsigned or that a signature is present but authenticity was not established. Verify a signed bundle against the externally obtained public key and render its authenticated status with:

```bash
./gradlew :droidproof-report:generateEvidenceReport \
  -Pdroidproof.bundlePath=/absolute/path/to/bundle \
  -Pdroidproof.reportPath=/absolute/path/to/report.html \
  -Pdroidproof.trustedPublicKeyPath="$DROIDPROOF_KEY_DIR/public-key.pem"
```

Supplying `droidproof.trustedPublicKeyPath` explicitly requests authentication. A missing/malformed signature, wrong key ID, changed manifest or timeline bytes, invalid signature, or failed bundle integrity produces a diagnostic-only report and a failing task. A public key copied from or delivered with an untrusted bundle would not provide an external trust root.

The HTML is a derived view and is not itself evidence. See [ADR 0007](docs/adr/0007-static-html-evidence-reports.md).

## Security and proof boundary

- The mock server binds only to IPv4 loopback. ADB reverse makes the stable device endpoint available without exposing the server to the LAN.
- Scenario-v3/v4 body limits are 1 byte through 1 MiB, response plans contain 1–16 responses, and accepted exchange observations are bounded. The checked-in sample uses 4096-byte request and response limits. The server retains at most the limit plus one detection byte while handling a request.
- The observed request body is read once and retained only internally while matching. Exchange evidence stores completeness, captured size, SHA-256, outcome, and issue codes—not the body. The expected body remains in scenario evidence and must use non-secret test data; hashes and sizes are identifying metadata, not anonymization.
- DroidProof proves what its controlled mock server observed. It does not prove that no other network traffic occurred. It is not a packet capture or TLS interception.
- Host, Android, and server wall clocks are not treated as a shared causal clock. The controlled server's sequence establishes order only among its own exchanges.
- Evidence hashes establish consistency with the manifest, not authenticity. For a signed bundle, successful integrity verification plus Ed25519 verification against an externally trusted public key authenticates the exact manifest/timeline core and transitively the manifest inventory. An unsigned bundle, or a signed bundle checked without an external trust key, makes no producer-authentication claim.
- Bundle authentication establishes limited provenance from possession of the corresponding private key. It is not non-repudiation, trusted timestamping, certificate validation, key ownership discovery, revocation, transparency logging, remote attestation, or proof that the recorded observations are true. Compromised signing keys and external trusted-key distribution remain operator concerns.
- Screenshots, UI hierarchy, scenario values, logcat, request metadata, response plans, and network evidence can contain sensitive test data. Keep bundles local or access-controlled; do not automatically upload them as public CI artifacts.

See [ADR 0008](docs/adr/0008-deterministic-network-evidence.md), [ADR 0009](docs/adr/0009-request-contract-verification.md), and [ADR 0010](docs/adr/0010-authenticated-evidence-bundles.md) for the network, authentication, and proof boundaries.

## Component status and remaining limitations

| Component | Status |
| --- | --- |
| Evidence model, writer, verifier | Implemented v1/v2/v3 integrity and optional authentication slice |
| Ed25519 bundle signing and external-key authentication | Implemented with authentication envelope v1 and JDK 17 |
| Read-only device capture | Implemented narrow ADB slice |
| Artifact-bound host smoke execution | Implemented for one APK, one selected emulator, and primary user 0 |
| Ordered View-based UI text/tap/assert | Implemented narrow resource-ID/exact-text slice |
| Deterministic loopback mock server and ADB reverse | Implemented for `POST /orders` ordered responses |
| Exact HTTP request-contract verification | Implemented for one v4 JSON body/media type on `POST /orders` |
| Real network exchange evidence and report section | Implemented with request contract outcome, issues, size, and SHA-256 |
| Emulator lifecycle management | Not implemented |
| Arbitrary traffic interception or TLS MITM | Not implemented |
| General endpoint scripting or generalized fault injection | Not implemented |
| Compose semantics, Espresso, or application probes | Not implemented |
| Published Gradle plugin, general CLI, or general-purpose scenario DSL | Not implemented |
| PKI, certificate chains, revocation, timestamping, transparency, KMS/HSM, or remote attestation | Not implemented |

The next recommended milestone is a narrow, explicitly recorded emulator-environment contract for locale, orientation, and animation settings, while preserving the current evidence and authentication formats.

## Architecture decisions

- [ADR 0001](docs/adr/0001-evidence-core.md): platform-independent evidence core
- [ADR 0002](docs/adr/0002-evidence-file-integrity.md): evidence-file inventory and verification
- [ADR 0003](docs/adr/0003-android-device-capture.md): bounded read-only Android capture
- [ADR 0004](docs/adr/0004-artifact-bound-android-smoke-execution.md): artifact-bound smoke execution
- [ADR 0005](docs/adr/0005-ordered-ui-steps.md): ordered UI steps and bounded tap
- [ADR 0006](docs/adr/0006-bounded-ui-text-entry.md): bounded text input
- [ADR 0007](docs/adr/0007-static-html-evidence-reports.md): verified static report
- [ADR 0008](docs/adr/0008-deterministic-network-evidence.md): deterministic mock-server network evidence
- [ADR 0009](docs/adr/0009-request-contract-verification.md): bounded HTTP request-contract verification
- [ADR 0010](docs/adr/0010-authenticated-evidence-bundles.md): optional Ed25519 authentication with an external trust root
