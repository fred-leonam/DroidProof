# DroidProof

**Artifact-bound, evidence-oriented verification for Android applications.**

DroidProof is an early Kotlin/JVM prototype. Its current vertical slice installs an exact APK on an explicitly selected emulator, executes a narrow ordered UI scenario, can run the sample application's real HTTP retry against a controlled local backend, captures UI and network observations, publishes an integrity-bound evidence bundle, verifies it, and renders an offline HTML report.

The APIs and schemas are not a stable release.

## Current implementation

- `droidproof-model` owns validated evidence identities, portable bundle paths, evidence descriptors, schema-v1/v2 legacy manifests, the schema-v3 execution manifest, and canonical timeline events.
- `droidproof-evidence` transactionally writes bundles, streams SHA-256 and byte-size inventory data, and verifies schemas v1, v2, and v3 with structured issues. Its synthetic sample now receives the authoritative Gradle project version.
- `droidproof-device` provides bounded ADB process execution and explicit screenshot, allowlisted metadata, and opt-in PID-filtered logcat collection.
- `droidproof-mock-server` is a small Android-independent JDK HTTP server. It binds only to `127.0.0.1`, uses an ephemeral host port, serves the narrow deterministic `POST /orders` response plan, records bounded exchanges, and exposes explicit start/inspect/stop lifecycle methods.
- `droidproof-host` performs preflight, exact APK byte binding, optional mock-server and serial-scoped ADB reverse setup, activity launch, ordered UI steps, screenshot capture, final APK identity checking, network evaluation, cleanup, bundle publication, and verification.
- `droidproof-report` verifies a bundle before rendering a deterministic static HTML report. Valid network bundles get a Network section with safe exchange metadata and links to verified exchange files. Invalid bundles get only a limited diagnostic report.
- `samples/smoke-app` preserves the v1/v2 greeting flow and adds a separate real HTTP order action. That action performs network I/O off the main thread, retries exactly once after HTTP 503, accepts the deterministic HTTP 201 JSON order ID, and displays `Order order-42 created`.

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
- Evidence schema v1 remains readable with a `FILE_INTEGRITY_UNAVAILABLE` warning.
- Evidence schema v2 retains its integrity-bound file inventory and synthetic writer.
- Evidence schema v3 remains the execution-bundle format. The network milestone does not bump it: `EvidenceFileRole.NETWORK`, `EventSource.MOCK_SERVER`, inventory bindings, and timeline evidence references already express the new observations.

Unknown scenario and evidence fields fail parsing. Scenario documents are bounded to 1 MiB and preserved byte-for-byte with their SHA-256. UI selectors remain package-qualified; input text remains restricted to 1–128 non-secret ASCII identifier characters.

## Synthetic evidence without Android

```bash
./gradlew :droidproof-evidence:generateSampleEvidence
```

This writes and verifies `droidproof-evidence/build/droidproof-samples/proof-checkout-offline-retry/`. It is a schema-v2 API sample; its network document is synthetic and is distinct from the real mock-server observations produced by scenario v3.

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

## Real deterministic network demonstration

The live task requires one local APK, the exact serial of an already-authorized emulator in primary user 0, and installed ADB. It does not create or boot an emulator.

Build the standalone non-debuggable sample release. Android Gradle Plugin 8.8.2 uses Gradle 8.10.2, JDK 17, and Android compile SDK 35. Generate a disposable local key outside the repository:

```bash
keytool -genkeypair -keystore /tmp/droidproof-smoke-keystore.jks \
  -storepass droidproof -keypass droidproof -alias droidproof-smoke \
  -keyalg RSA -keysize 2048 -validity 3650 \
  -dname 'CN=DroidProof Local Sample'

DROIDPROOF_SAMPLE_STORE_PASSWORD=droidproof \
DROIDPROOF_SAMPLE_KEY_PASSWORD=droidproof \
./gradlew -p samples/smoke-app assembleRelease \
  -Pdroidproof.sample.keystore=/tmp/droidproof-smoke-keystore.jks \
  -Pdroidproof.sample.keyAlias=droidproof-smoke
```

Confirm the emulator serial, then run the checked-in scenario-v3 demonstration:

```bash
./gradlew :droidproof-host:runSmokeScenario \
  -Pdroidproof.apkPath=samples/smoke-app/build/outputs/apk/release/DroidProofSmokeApp-release.apk \
  -Pdroidproof.scenarioPath=samples/smoke-app/scenarios/network-passing.json \
  -Pdroidproof.deviceSerial=emulator-5554 \
  -Pdroidproof.adbPath=/absolute/path/to/Android/Sdk/platform-tools/adb
```

The scenario enters `DroidProof42`, taps `order_action`, and asserts `Order order-42 created`. During execution DroidProof:

1. starts `droidproof-mock-server` on an ephemeral `127.0.0.1` host port;
2. creates `adb -s <serial> reverse tcp:38637 tcp:<host-port>`;
3. observes the application's first `POST /orders` and returns HTTP 503;
4. observes its one deterministic retry and returns HTTP 201 with `{"orderId":"order-42"}`;
5. evaluates both the ordered server exchange sequence and final UI assertion;
6. removes only the owned reverse mapping and stops the owned server; and
7. publishes and verifies the evidence bundle.

There is no fallback to LAN or uncontrolled networking if setup fails. Reverse removal and server shutdown are attempted after success, assertion failure, host failure, timeout, and cancellation. Cleanup errors do not replace the original failure.

The task writes a fresh directory below `droidproof-host/build/droidproof-runs/` and prints its location. A successful network run has a layout equivalent to:

```text
bundle/
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

Each network exchange document comes from the actual controlled server observation and contains a host observation timestamp, server sequence, bounded method/path, bounded request and response body metadata, response status, and response-plan match metadata. Request and response bodies are not copied into exchange documents; configured response bodies remain in the exact scenario evidence. Every network file is inventoried with role `network`, byte size, and SHA-256, and every server event links to its file from the canonical timeline.

Success requires completed execution, matched UI assertion, matched ordered network expectation, matching APK bytes before and after, complete required evidence, and successful bundle verification. A UI pass with a network mismatch and a network match with a UI failure are both behavioral failures. Infrastructure failure or cancellation yields `NOT_EVALUATED`, rather than a fabricated behavioral result.

The v1 `passing.json`/`failing.json` and v2 `interactive-passing.json`/`interactive-failing.json` demonstrations remain available. Add `-Pdroidproof.replaceExisting=true` only when intentionally replacing different installed bytes for the target package.

## Offline HTML report

Generate a report from a preserved bundle, using paths outside that bundle:

```bash
./gradlew :droidproof-report:generateEvidenceReport \
  -Pdroidproof.bundlePath=/absolute/path/to/bundle

./gradlew :droidproof-report:generateEvidenceReport \
  -Pdroidproof.bundlePath=/absolute/path/to/bundle \
  -Pdroidproof.reportPath=/absolute/path/to/report.html
```

The default output is `droidproof-report/build/reports/droidproof/evidence-report.html`. The report has inline CSS, no JavaScript, and no external resources. It shows verified execution, artifact, environment, timeline, screenshot, inventory, and network metadata. Network bodies are not injected into HTML; exchange rows link to verified local evidence files. If any registered network or other evidence is missing or tampered, verification fails and the report omits all unverified artifact, scenario, timeline, network, and preview content.

The HTML is a derived view and is not itself evidence. See [ADR 0007](docs/adr/0007-static-html-evidence-reports.md).

## Security and proof boundary

- The mock server binds only to IPv4 loopback. ADB reverse makes the stable device endpoint available without exposing the server to the LAN.
- Scenario-v3 body limits are 1 byte through 1 MiB, response plans contain 1–16 responses, and accepted exchange observations are bounded. The checked-in sample uses 4096-byte request and response limits.
- Network evidence proves what DroidProof's controlled mock server observed and which configured response it selected. It is not packet capture, arbitrary traffic interception, proof that no other calls occurred, TLS interception, or a globally synchronized causal trace.
- Host, Android, and server wall clocks are not treated as a shared causal clock. The controlled server's sequence establishes order only among its own exchanges.
- Evidence hashes establish consistency with the manifest, not authenticity. Someone able to rewrite both evidence and manifest can create another self-consistent bundle.
- Screenshots, UI hierarchy, scenario values, logcat, request metadata, response plans, and network evidence can contain sensitive test data. Keep bundles local or access-controlled; do not automatically upload them as public CI artifacts.

See [ADR 0008](docs/adr/0008-deterministic-network-evidence.md) for the network design and proof boundary.

## Component status and remaining limitations

| Component | Status |
| --- | --- |
| Evidence model, writer, verifier | Implemented narrow JVM slice |
| Read-only device capture | Implemented narrow ADB slice |
| Artifact-bound host smoke execution | Implemented for one APK, one selected emulator, and primary user 0 |
| Ordered View-based UI text/tap/assert | Implemented narrow resource-ID/exact-text slice |
| Deterministic loopback mock server and ADB reverse | Implemented for `POST /orders` ordered responses |
| Real network exchange evidence and report section | Implemented for the controlled server observations |
| Emulator lifecycle management | Not implemented |
| Arbitrary traffic interception or TLS MITM | Not implemented |
| General endpoint scripting or generalized fault injection | Not implemented |
| Compose semantics, Espresso, or application probes | Not implemented |
| Published Gradle plugin, general CLI, or general-purpose scenario DSL | Not implemented |
| Evidence signing or external trust root | Not implemented |

The next logical slice is broader controlled endpoint behavior and correlation without weakening the current artifact, lifecycle, and evidence boundaries—not arbitrary interception or a general scripting system.

## Architecture decisions

- [ADR 0001](docs/adr/0001-evidence-core.md): platform-independent evidence core
- [ADR 0002](docs/adr/0002-evidence-file-integrity.md): evidence-file inventory and verification
- [ADR 0003](docs/adr/0003-android-device-capture.md): bounded read-only Android capture
- [ADR 0004](docs/adr/0004-artifact-bound-android-smoke-execution.md): artifact-bound smoke execution
- [ADR 0005](docs/adr/0005-ordered-ui-steps.md): ordered UI steps and bounded tap
- [ADR 0006](docs/adr/0006-bounded-ui-text-entry.md): bounded text input
- [ADR 0007](docs/adr/0007-static-html-evidence-reports.md): verified static report
- [ADR 0008](docs/adr/0008-deterministic-network-evidence.md): deterministic mock-server network evidence
