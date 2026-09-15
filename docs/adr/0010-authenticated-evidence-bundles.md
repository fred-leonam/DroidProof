# ADR 0010: Authenticated evidence bundles

## Status

Accepted.

## Context and threat model

The schema-v2/v3 SHA-256 inventory establishes evidence integrity relative to `manifest.json`. It detects missing, added, or changed registered evidence when the manifest is held fixed. It does not establish who produced the bundle: an attacker able to replace evidence and the manifest can create a different internally consistent bundle.

This decision adds an optional provenance layer. A verifier that obtains an Ed25519 public key through a trusted channel outside the bundle can determine whether the exact bundle core was signed by the corresponding private key. The external distribution, ownership, and trust decision for that public key are deliberately outside DroidProof.

An embedded public key is not a trust root. The bundle does not contain a public key, and a verifier must never infer trust from key material supplied by the bundle itself.

## Authentication envelope and signed message

Evidence manifest schemas v1, v2, and v3 are unchanged. Authentication uses the separate root core file `authenticity.json`, with strict authentication schema version 1. It is a reserved core file, not an ordinary evidence file and not an entry in `manifest.json`; this avoids a recursive signature dependency.

The version-1 envelope contains:

- `schemaVersion`, exactly `1`;
- `algorithm`, exactly `Ed25519`;
- `keyId`, the lower-case hexadecimal SHA-256 of the complete X.509 SubjectPublicKeyInfo encoding returned by `PublicKey.getEncoded()`;
- `coreFiles`, exactly two entries in this order: `manifest.json`, then `timeline.json`, each with the SHA-256 and byte size of its complete bytes; and
- `signature`, the standard padded Base64 encoding of the Ed25519 signature.

After `manifest.json` and `timeline.json` have been completely written, the writer hashes and measures their exact bytes. It signs this deterministic US-ASCII, domain-separated message (where `LF` is one byte `0x0a` and sizes are minimal base-10 ASCII):

```text
DroidProof authenticated evidence bundle v1 LF
manifest.json LF
<manifest byte size> LF
<manifest lower-case SHA-256> LF
timeline.json LF
<timeline byte size> LF
<timeline lower-case SHA-256> LF
```

The signature is therefore over a deterministic digest-and-size description rather than reserialized JSON. Any byte-level change, including semantically equivalent whitespace changes, changes the authenticated core description. Authenticating the exact manifest transitively authenticates its evidence inventory when normal bundle integrity verification also succeeds.

Ed25519 is provided by JDK 17, is deterministic for a given key and message, and needs no third-party cryptography dependency.

## Trust, keys, and verification semantics

Signing accepts both a PKCS#8 Ed25519 private key and its X.509/SPKI Ed25519 public key so the writer can record and self-check the public key ID. DER and clean PEM wrappers (`PRIVATE KEY` and `PUBLIC KEY`) are supported. Key files are bounded regular files opened without following symbolic links. Private-key bytes, key paths, and private-key-derived diagnostics are never written to the bundle, result documents, timeline, report, or logs.

Integrity and authenticity are separate results:

- no envelope and no trusted key: `UNSIGNED`;
- a structurally valid signed envelope but no external trusted key: `SIGNED_UNTRUSTED` (signature presence is not trust);
- a matching externally supplied public key, matching core bytes, and valid signature: `AUTHENTICATED`;
- a malformed or unsupported claimed envelope, unsafe envelope file, mismatched core metadata, wrong key ID, invalid signature, or a missing envelope when trusted authentication was requested: `INVALID`.

Supplying a trusted public key explicitly requests trusted authentication and fails closed. Authentication never succeeds unless normal bundle integrity verification also succeeds. A malformed claimed `authenticity.json` is reported deterministically even when no trust key was supplied; it is not treated as unsigned. Existing unsigned v1/v2/v3 bundles remain readable and retain their previous integrity result when authentication was not requested.

## Publication and reporting

`EvidenceBundleWriter` remains the publication boundary. It copies and inventories evidence, writes the final manifest and timeline, optionally writes the envelope from their exact bytes, verifies the staged bundle (including the signing public key when signing), and only then installs it transactionally.

The smoke entry point accepts both `droidproof.signingPrivateKeyPath` and `droidproof.signingPublicKeyPath`, or neither. The report task accepts an optional external `droidproof.trustedPublicKeyPath`. Without that external key, reports say either unsigned or signed but not authenticated. If a trusted key is supplied and authentication fails, the diagnostic report omits evidence and execution claims.

## Explicit proof boundary and limitations

This feature proves only that the exact manifest/timeline core description was signed by possession of the private key corresponding to an externally trusted public key, and—when integrity verification succeeds—that registered evidence agrees with the authenticated manifest. It does not prove that observations are true, complete, simultaneous, causally ordered, or produced by uncompromised software or hardware.

It is provenance/authentication, not legal or cryptographic non-repudiation, trusted timestamping, certificate or chain validation, key ownership discovery, key rotation or revocation, transparency logging, remote attestation, APK-signing validation, or protection after the private key is compromised. The envelope carries no trustworthy signing time. Private-key storage, access control, backup, rotation, revocation, and trusted public-key distribution remain operator responsibilities.
