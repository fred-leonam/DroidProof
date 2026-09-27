# ADR 0024: Certificate-backed bundle authentication

## Status

Accepted.

## Decision

`authenticity.json` schema version 1 remains the Ed25519 public-key envelope described by ADR 0010. Schema version 2 adds `certificateChain`, an ordered, leaf-first list of padded Base64 DER X.509 certificates. The leaf's SPKI SHA-256 must equal `keyId`; its public key verifies the Ed25519 signature. The chain is an untrusted bundle input.

V2 signs the exact v1 core description plus a separate v2 domain separator and, in order, each certificate's DER length and SHA-256. Consequently certificates cannot be inserted, removed, reordered, or swapped without invalidating the signature. The signed core remains the exact byte size and SHA-256 of `manifest.json` and `timeline.json`.

The verifier accepts external trust-anchor certificates and CRLs only from regular, non-symbolic-link, bounded files. It uses JDK 17 `CertificateFactory`, `CertPathValidator` and PKIX parameters with revocation disabled, then performs the bounded offline CRL check. It never fetches AIA certificates, CRLs, or OCSP responses. Chains are limited to eight certificates; external certificate and CRL inputs are limited to one MiB.

At an explicit evaluation instant, PKIX validates signatures, issuer linkage, validity, and configured roots. DroidProof additionally requires an end-entity leaf (`basicConstraints < 0`) with `digitalSignature` when key usage is present, and CA issuers with CA basic constraints and `keyCertSign` when key usage is present. This is the producer-signing policy.

For each non-anchor path certificate, a current CRL must have the matching issuer, a valid signature under that issuer, `thisUpdate <= evaluation time`, and no expired `nextUpdate`; its serial is then checked. The result is `GOOD`, `REVOKED`, or `UNKNOWN`; absent, stale, forged, or inapplicable CRLs are `UNKNOWN`, never good. A current CRL is only a statement at evaluation time. The bundle has no trusted signing timestamp, so it cannot establish historical non-revocation.

## Claims and policy

Integrity, signature validity, chain trust, and revocation are separate report fields. A valid embedded signature or chain alone is not an authenticated producer claim. With configured external roots and a valid chain, the report says it authenticated the signing key under that configured policy. Strict policy can require trusted chain and/or `GOOD` revocation and fails closed, producing a diagnostic report without execution or evidence claims. The older supplied-public-key route remains an explicit direct-key authentication claim.

This does not establish signer identity outside the configured roots and policy, truthful execution, trusted time, historical non-revocation, transparency logging, remote attestation, or private-key custody.
