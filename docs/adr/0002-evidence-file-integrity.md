# ADR 0002: Evidence-file integrity

## Status

Accepted.

## Context

Schema version 1 records deterministic manifest and timeline documents, but timeline evidence references are only paths. A bundle can therefore lose, gain, or corrupt an evidence file without the manifest revealing the change. Bundle replacement also wrote directly into an existing destination and could leave a mixture of old and new files after failure.

## Decision

Schema version 2 adds an `evidenceFiles` inventory to the manifest. Each entry contains a validated bundle-relative path, lower-case SHA-256 digest, byte size, media type, and optional logical role. Entries are serialized in lexicographic path order. The writer derives the inventory from bytes copied into the bundle; callers cannot provide precomputed descriptors to bypass ingestion.

Paths use forward slashes and Unicode NFC normalization. Absolute paths, drive-qualified paths, empty segments, `.` and `..`, backslashes, Windows-invalid characters and device names, trailing dots or spaces, case-insensitive collisions, and the reserved root outputs `manifest.json` and `timeline.json` are rejected. Host source paths are never serialized. Sources and verified evidence must be regular files, and file access does not follow symbolic links.

The verifier returns a structured result with stable issue codes and error or warning severity. For version 2 it validates the core JSON documents before trusting inventory paths, cross-checks timeline references and media types, hashes registered files as streams, and reports missing, corrupt, unsafe, duplicated, symbolic-link, and unexpected content.

New and replacement writes are transactional at bundle granularity. The writer fully constructs a sibling staging directory first. For replacement, it renames the previous destination to a sibling backup, installs the completed staging directory, restores the backup if installation fails, and removes the backup after success. Filesystem move operations are isolated so rollback behavior is tested deterministically. Atomic moves are requested where supported, with same-filesystem rename as the fallback.

## Compatibility policy

Writers emit only schema version 2 and reject requests for other versions. Readers explicitly support versions 1 and 2; no version 1 document is silently treated as version 2. A valid version 1 bundle remains readable, but verification returns a `FILE_INTEGRITY_UNAVAILABLE` warning because version 1 has no cryptographic file inventory. Unknown schema versions are errors.

Changing animation scales from integers to finite, non-negative floating-point values is source and binary incompatible for callers constructing `AnimationConfiguration`, but it preserves the meaning of integer JSON values and correctly models Android values such as `0.5` and `1.0`. Android API-level validation now accepts every positive value instead of imposing a yearly upper bound.

## Consequences and limitations

Bundles can now prove the presence, size, and content of copied evidence files and identify unexpected additions. The manifest and timeline themselves are required and validated, but this slice does not add signing or an external trust root for the manifest. A party able to rewrite both an evidence file and its manifest descriptor can still create a self-consistent bundle.

Android execution, ADB and emulator control, evidence collection, mock-server operation, HTML reporting, and a command-line interface remain outside this decision.
