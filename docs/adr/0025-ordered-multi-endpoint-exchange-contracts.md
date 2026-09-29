# ADR 0025: Ordered multi-endpoint exchange contracts

## Status

Accepted.

## Decision

Schema v7 adds one bounded backend plan containing one to sixteen uniquely named exchanges. Each exchange declares a stable ID, exact `GET` or `POST` method, exact origin-form target, optional exact UTF-8 JSON request body/media-type contract, and one JSON response with the existing bounded delay or connection-close fault. A missing request contract means bodyless: the received body must be exactly zero bytes and no content type is accepted. JSON bodies are compared as bytes; query encoding and query order are raw target text and are significant.

The target must start with one slash and be no longer than 2048 characters. Absolute URLs, scheme-relative targets, fragments, and control characters are rejected before server start or ADB reverse setup. Existing v1-v6 plans retain the single `POST /orders` semantics and response-consumption behavior.

The one loopback server remains single-threaded and is connected through one serial-scoped reverse mapping. For v7 it compares each complete request with the next unconsumed exchange. Only a complete full match consumes it and receives the planned response. A mismatch receives a bounded deterministic diagnostic response, remains recorded against that plan position, and cannot later become a passing run. An incomplete or over-limit read is recorded as `NOT_EVALUATED` and does not consume an exchange. Extra requests are observations and make the final verdict fail. A pass requires exactly the declared observed sequence, with no missing or extra exchanges. Arrival order is server-observed order; concurrent application request determinism is not promised.

Exchange evidence contains no raw request body. It records observed method/target, body size/hash/completeness, contract outcome/issues, planned exchange ID/position, fault, and response outcome. Inventory and timeline bind each actual server observation. Server writes prove neither that the client received a response nor global network causality. Old bundles remain readable; new optional fields preserve their existing evidence documents.

## Consequences

This is finite declarative endpoint scripting only. It does not add routing maps, branching, variables, regexes, arbitrary code, multiple ports, interception, or concurrent matching.
