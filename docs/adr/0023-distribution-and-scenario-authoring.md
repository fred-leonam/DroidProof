# ADR 0023: Distribution and scenario authoring surfaces

## Context

DroidProof's complete runner was available only through tasks declared inside this repository. Consumers could neither apply a versioned plugin nor install a command-line launcher, and scenario authors had to maintain JSON by hand.

## Decision

Three JVM 17 artifacts expose the existing runner without creating a second execution engine.

`droidproof-cli` is an Application distribution with `run`, `validate-scenario`, `report`, `recover`, and `probe-android-cli` commands. It accepts conventional long options, maps them into the existing validated configurations, and uses distinct exit statuses for usage and operation failures.

`droidproof-gradle-plugin` publishes plugin ID `io.github.fredleonam.droidproof`. Its typed `droidProof` extension feeds `droidProofRun`, `droidProofValidateScenario`, and `droidProofReport`. Run and report tasks remain explicitly non-cacheable because they inspect mutable external state or verify caller-selected evidence at execution time.

`droidproof-scenario-dsl` is a typed Kotlin builder for schema v6. It produces an immutable `ScenarioDocument`, deterministic pretty JSON, and an explicit `writeTo` operation. The builder covers every currently executable step and controlled backend option. It delegates validation to the same scenario and mock-server value types used by the host.

All artifacts use the repository's Maven group and version. Maven publications are available for the CLI and DSL, while the Java Gradle Plugin machinery creates the plugin marker and implementation publications.

## Boundaries

The authoring DSL is general across applications and current DroidProof primitives. It does not add arbitrary device commands, selectors, network endpoints, scripts, interpolation, secrets, or new evidence claims. Schema v6 remains the wire contract, and the strict scenario loader remains the acceptance boundary before execution.

The CLI and plugin call the same coordinators as the repository tasks. They do not install Android tools, authorize devices, download SDK packages, weaken target ownership checks, or bypass evidence verification.
