# ADR 0008: Deterministic mock-server network evidence

## Status

Accepted.

## Context

The artifact-bound smoke runner can execute ordered UI steps and retain verified UI evidence, but a passing UI assertion says nothing about whether an application made the intended HTTP calls. The first network milestone needs a real Android HTTP interaction with deterministic failure and retry behavior without introducing Internet access, LAN exposure, a general server framework, arbitrary scenario scripting, or traffic interception.

## Decision

Add the Android-independent `droidproof-mock-server` JVM module. It uses the JDK HTTP server only, binds explicitly to IPv4 loopback `127.0.0.1`, chooses an ephemeral host port, serves the narrow `POST /orders` contract, records bounded observations, and has an explicit start/inspect/stop lifecycle. Scenario schema v3 describes a stable device port, request and response byte limits, and an ordered `responsePlan`. The first checked-in plan returns JSON with HTTP 503 and then JSON with HTTP 201. Methods, paths, ports, statuses, response count, JSON response bodies, media types, and byte limits are strictly validated; unknown JSON fields fail parsing. Scenario schemas v1 and v2 retain their existing meaning.

The server is not exposed on a LAN interface. The host creates a bounded, serial-scoped ADB reverse mapping from `127.0.0.1:<device-port>` on the selected emulator to `127.0.0.1:<ephemeral-host-port>`. Reverse setup and removal use the same argument-based command runner, timeout limits, cancellation mapping, output limits, serial validation, and sanitized failure reporting as other device operations. Network-enabled execution never falls back to an uncontrolled endpoint when setup fails.

The host always attempts to remove an established reverse mapping and stop its owned server on success, behavioral failure, execution error, timeout, and cancellation. Cleanup failures are recorded but do not replace the original failure. The server is stopped before its final immutable observation snapshot is converted to evidence.

Each observed exchange is stored at `network/exchanges/NNN.json`. The document records the server sequence, host observation timestamp, bounded method and request target, request-body byte/hash metadata, selected response status, response-body byte/hash metadata, and whether the ordered plan supplied that response. Bodies and headers are not copied into the exchange document, reducing accidental disclosure while retaining bounded content identity metadata. The original scenario document remains evidence and therefore contains its configured response bodies.

Every exchange file is ingested through the existing evidence writer with role `network`, byte size, and SHA-256. A corresponding `MOCK_SERVER` timeline event links to that integrity-bound file. The execution result has an explicit network outcome: `MATCHED`, `MISMATCHED`, or `NOT_EVALUATED`. A network-enabled scenario passes only when the UI assertion matches, the complete ordered server sequence matches, artifact binding holds, required UI/network evidence is complete, and bundle verification succeeds. A missing, unexpected, wrong-method, wrong-path, oversized, or extra request is a behavioral mismatch when execution otherwise reaches evaluation; infrastructure failure or cancellation is not relabeled as a behavioral mismatch.

Server sequence is authoritative only for the order in which this controlled server handled requests. Host, Android, and server wall clocks are not assumed to be synchronized causal clocks. Timeline timestamp sorting provides a stable presentation; it does not strengthen those timestamps into a cross-process causality proof.

## Limits and proof boundary

Request and response bodies are each configurable from 1 byte through 1 MiB. A scenario has 1 through 16 planned responses, and the server has a bounded exchange count with a small allowance for unexpected requests. The checked-in demonstration uses 4096-byte body limits and two planned responses.

The transcript proves that DroidProof's controlled server observed the recorded bounded HTTP exchanges and selected the recorded responses during this run, subject to the integrity and trust limits of the bundle. It does not prove that the Android application made no other network calls, identify packets below HTTP, continuously attest application identity, authenticate the evidence producer, or establish globally synchronized causality. Recorded scenario or body metadata may still concern sensitive test data and must be handled as such.

Arbitrary traffic interception, proxying, TLS man-in-the-middle operation, certificate installation, generalized fault injection, endpoint scripting, and production-server concerns are explicitly outside this milestone.

## Consequences

The implementation remains a small vertical slice: host execution depends on the mock-server module, while the mock server does not depend on host, device, evidence, report, or Android code. Evidence remains schema v3 because existing inventory and timeline contracts already support network-role files and `MOCK_SERVER` events. Reports render verified network metadata and local evidence links without embedding response JSON; invalid bundles retain the diagnostic-only report behavior.
