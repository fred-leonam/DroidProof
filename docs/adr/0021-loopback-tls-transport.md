# ADR 0021: Explicit loopback TLS transport

## Status

Accepted.

## Decision

The controlled `/orders` endpoint may use `HTTPS` when that transport is explicitly declared in the scenario. The host binds only IPv4 loopback and reaches the Android target only through the existing serial-scoped ADB reverse mapping. The sample app trusts its bundled loopback test certificate only for `127.0.0.1`; DroidProof does not install that certificate in either Android trust store.

The bundled identity is public sample material, not a secret or a production certificate. It exists solely to exercise TLS transport on the fixed ADB-reversed endpoint. Exchange evidence remains bounded request/response metadata and hashes.

## Consequences

This is TLS termination for a declared, controlled endpoint—not TLS MITM. DroidProof does not set an Android global proxy, create certificates for arbitrary hosts, inspect unrelated traffic, bypass certificate pinning, or capture encrypted traffic without an application explicitly trusting the test endpoint. A general interception or CA-installation design requires a separate consent, restoration, and data-handling decision.
