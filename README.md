# DroidProof

**Reproducible, evidence-oriented verification for Android applications.**

DroidProof is a planned open-source Android verification harness that will turn test executions into artifact-bound, human-readable, and machine-readable evidence. It will correlate application identity, device configuration, UI state, screenshots, semantics, logs, and network activity to show how a specific Android build behaved under a defined scenario.

> [!IMPORTANT]
> DroidProof is currently in early development. The JVM-only evidence core is implemented; the Android-facing APIs and modules below remain planned and are not yet available as a stable release.

## Current implementation

The first executable slice provides platform-independent Kotlin/JVM modules:

- `droidproof-model` defines validated identities, a versioned evidence manifest, environment contract, and timeline events.
- `droidproof-evidence` computes streaming SHA-256 digests and writes deterministic, human-readable `manifest.json` and `timeline.json` bundles.

Build and test it with JDK 17:

```bash
./gradlew check
```

Generate the deterministic checkout retry example:

```bash
./gradlew :droidproof-evidence:generateSampleEvidence
```

The generated bundle is at `droidproof-evidence/build/droidproof-samples/proof-checkout-offline-retry/`. It is build output and is not committed.

## Motivation

A conventional test result usually says that a test passed or failed. It often does not preserve enough context to answer:

- Which APK or App Bundle was tested?
- Which device, API level, locale, scenario data, and environment were used?
- What happened across the UI, application, network, and system layers?
- Which evidence supports each behavioral assertion?
- Can the same execution be reproduced later?
- Can a CI pipeline or coding agent interpret the result without parsing raw logs?

DroidProof aims to provide that missing evidence and reproducibility layer. It is not intended to replace JUnit, Compose Test, Espresso, UI Automator, device farms, or performance tools. It will coordinate and enrich them.

## Planned architecture

```mermaid
flowchart TB
    Gradle["Gradle plugin"] --> Coordinator["Host coordinator"]
    Coordinator --> Device["Emulator or device"]
    Coordinator --> MockServer["Mock server"]
    Device --> App["App + optional probe"]
    App --> Evidence["Evidence collectors"]
    MockServer --> Evidence
    Evidence --> Report["HTML and JSON report"]
```

### Components

- **Gradle plugin:** discovers scenarios, resolves build variants, and exposes DroidProof tasks.
- **Host coordinator:** controls the execution lifecycle and correlates events across processes.
- **Device adapter:** installs artifacts and controls emulators or physical devices.
- **Mock server:** provides deterministic backend responses and controlled failure conditions.
- **Optional application probe:** exposes selected test hooks in non-production builds.
- **Evidence collectors:** capture screenshots, semantics, logcat, network exchanges, and environment metadata.
- **Report generator:** produces evidence for developers, CI systems, and automated agents.

## Core model

DroidProof will treat verification as three versioned inputs and one structured output:

```text
Android artifact + scenario + environment contract -> evidence bundle
```

An evidence bundle is expected to contain:

```text
proof-checkout-offline-retry/
├── manifest.json
├── timeline.json
├── junit-results.xml
├── screenshots/
├── semantics/
├── network.har
├── logcat.txt
└── report.html
```

The manifest will bind the result to information such as:

- APK or App Bundle hash;
- signing-certificate fingerprint;
- Git commit;
- scenario and scenario-data hashes;
- device fingerprint and API level;
- locale, orientation, and animation configuration;
- random seed and controlled clock, when available;
- DroidProof version.

## Key differentiators

### Artifact-bound verification

Every execution will identify the exact application artifact and environment that produced the result.

### Evidence graph

Assertions will be connected to their supporting evidence instead of being presented only as pass/fail values or unrelated attachments.

For example, the claim `order-created-once` may be supported by one HTTP request, one accepted response, a corresponding semantic UI state, and the absence of a duplicate retry.

### Cross-layer timeline

DroidProof will correlate events from the host, Android system, application process, test process, and mock server into a causal execution timeline.

### Deterministic fault injection

Later releases are expected to support repeatable failures positioned around meaningful events, such as terminating a connection after the server commits a request but before the client receives the response.

### Machine-readable results

The JSON evidence format will allow CI pipelines and coding agents to identify the first behavioral divergence without interpreting screenshots or unstructured logs.

## Planned execution modes

| Mode | Artifact | Intended visibility |
| --- | --- | --- |
| Black box | Exact signed release APK | Externally observable behavior |
| Proof release | Minified, non-debuggable release-like build with selected hooks | UI, network, and controlled internal evidence |
| Instrumented | Debug/test build with the optional probe | Maximum development diagnostics |

Black-box execution will use out-of-process Android testing capabilities so that release builds can be exercised without modifying or weakening the APK.

## Proposed scenario API

The initial API will integrate with Kotlin and JUnit rather than introduce a separate scenario language.

```kotlin
@get:Rule
val proof = DroidProofRule(
    scenarioId = "checkout-offline-retry"
)

@Test
fun submitAfterConnectivityReturns() = proof.run {
    environment {
        locale("en-US")
        orientation(Orientation.PORTRAIT)

        backend {
            get("/checkout/config")
                .respond(scenarioData("checkout.json"))

            post("/orders")
                .respondSequence(
                    httpError(503),
                    json("order-created.json")
                )
        }
    }

    execute {
        launchDeepLink("sample://checkout/cart-42")
        onElement("customer-name").typeText("Alex")
        onElement("submit").click()
        process.kill()
        process.relaunch()
    }

    verify {
        screen("order-created")
        request("/orders").wasSentExactlyOnce()
        noUnhandledExceptions()
    }
}
```

This API is illustrative and will evolve through executable prototypes and user feedback.

## Proposed modules

```text
droidproof/
├── droidproof-model/             # Scenario and evidence models
├── droidproof-gradle-plugin/     # Gradle tasks and variant integration
├── droidproof-host/              # Host-side coordinator
├── droidproof-device/            # Device and emulator control
├── droidproof-runner/            # Instrumentation integration
├── droidproof-ui-automator/      # Black-box interactions
├── droidproof-compose/           # Compose semantic evidence
├── droidproof-probe/             # Optional build-time application bridge
├── droidproof-network/           # Recording and fault injection
├── droidproof-mock-server/       # Deterministic backend simulation
├── droidproof-evidence/          # Evidence collection and correlation
├── droidproof-report/            # HTML and JSON reports
└── samples/                       # Demonstration Android applications
```

## Technology direction

The first implementation is expected to use:

- Kotlin;
- Gradle Plugin API;
- JUnit;
- AndroidX Test and UI Automator;
- Compose testing and semantics APIs;
- Kotlin coroutines;
- Kotlin serialization;
- a local JVM mock server;
- static HTML and JSON report generation.

## Contributing

DroidProof is currently being shaped through architecture experiments. Design discussions, use cases, failure scenarios, and feedback about Android verification workflows will be welcome once the initial repository structure is available.
