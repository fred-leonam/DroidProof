# ADR 0009: Bounded HTTP request-contract verification

## Status

Accepted.

## Context

Scenario v3 proves that DroidProof's controlled server observed an ordered request/response sequence, but it does not prove that the application sent the intended request content. A successful final screen can therefore coexist with an incorrect customer payload. The next narrow slice must connect the application's real HTTP bytes to an explicit, versioned expectation without creating a general HTTP scripting language or copying potentially sensitive request bodies into every exchange document.

## Decision

Scenario schema v4 adds one required `expectedRequest` to the existing single `POST /orders` backend plan. It contains an explicit media type and non-empty JSON body. The expected UTF-8 body must fit the scenario's existing request-body limit. Scenario documents remain limited to 1 MiB, reject unknown fields, and are preserved byte-for-byte as evidence. Scenario v1, v2, and v3 decoding and meaning are unchanged; in particular, v3 does not accept or evaluate `expectedRequest`.

The accepted request media-type surface is deliberately small. The expected value must be `application/json`, optionally with exactly one unquoted `charset=utf-8` parameter. Type, subtype, parameter name, and the UTF-8 charset token are compared case-insensitively after parsing. Optional whitespace around the separator and equals sign is normalized. Parameter presence is significant: `application/json` and `application/json; charset=utf-8` are not equivalent. Additional, quoted, duplicated, unknown, non-UTF-8, control-character, or malformed parameters are rejected. Observed valid-but-different media types are mismatches; missing and malformed observed values are reported separately.

The mock server performs one bounded read. It retains the captured bytes only in an internal `BoundedBodyRead` while handling the exchange and derives byte size, SHA-256, and completeness from exactly those bytes. Allocation is capped at the configured limit plus one detection byte. No second request-body read occurs. A request contract matches only when method and path match exactly, the media type matches under the narrow rules above, the read is complete, and both body byte size and SHA-256 match the expected UTF-8 bytes. The comparison is byte-exact, not semantic JSON equivalence.

An exchange records `MATCHED`, `MISMATCHED`, or `NOT_EVALUATED` plus deterministic issue codes that distinguish method, path, media type, body size, body hash, incomplete collection, and limit excess. Incomplete or over-limit body collection is `NOT_EVALUATED`, not a fabricated body mismatch. The response plan remains independent of a complete body or media-type mismatch once the method, path, and body bound permit the planned exchange. This allows a deliberately wrong expected body to exercise the same 503/201 application retry and successful UI while the overall scenario fails.

For v4, host network evaluation requires the exact exchange count, ordered response-plan match, complete bounded request/response metadata, matching request size and SHA-256 metadata, and `MATCHED` request-contract status for every exchange. A request-contract mismatch after otherwise normal execution is a behavioral failure: execution remains `COMPLETED`, the scenario verdict is `FAILED`, and a complete integrity-valid bundle can still be published. If required request collection is unavailable, the network and scenario are not treated as evaluated and the existing execution-failure distinction is preserved. Missing or extra requests remain behavioral sequence mismatches.

Network exchange evidence does not duplicate either expected or observed request body bytes. The exact expected body already exists in the integrity-inventoried scenario document. Each exchange document contains safe bounded metadata and contract results, is inventoried with role `network`, and remains timeline-linked. The verified HTML report displays request-contract outcome, issue codes, observed byte count, SHA-256, and response status, but never the request body. Invalid or tampered bundles retain diagnostic-only, fail-closed reporting.

The checked-in passing scenario deliberately repeats `DroidProof42` in the UI input step and in the explicit expected JSON body. There is no interpolation or dynamic binding; this duplication is intentional, deterministic, and reviewable.

## Privacy and proof boundary

Not duplicating request bodies reduces disclosure and amplification inside a bundle, but the expected body is still present in the scenario evidence and must contain only non-secret test data. SHA-256 and sizes are identifying metadata, not anonymization. The implementation does not log request bodies or include them in network exchange JSON or HTML.

DroidProof proves what its controlled mock server observed. It does not prove that no other network traffic occurred. It is not a packet capture. It is not TLS interception. It does not authenticate the producer of the bundle.

It also does not provide arbitrary endpoints, headers, scripts, templates, expressions, regular expressions, JSONPath, variable interpolation, proxying, VPN capture, HTTP/2-specific tooling, streaming HTTP, WebSockets, or multiple concurrent backends.

## Consequences

The evidence-bundle schema remains v3 because its inventory, `network` role, execution result, and timeline references already integrity-bind these richer exchange documents. Consumers must interpret request-contract status as a controlled-server observation, not as general traffic coverage or producer authentication. Future dynamic correlation between UI values and request expectations requires a separate design decision.
