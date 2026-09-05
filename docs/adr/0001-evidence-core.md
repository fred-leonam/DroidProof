# ADR 0001: Platform-independent deterministic evidence core

## Status

Accepted.

## Context

DroidProof ultimately coordinates Android devices, test frameworks, and controlled backend behavior. The evidence bundle itself, however, must be readable, validated, and comparable in CI and other host-side tools without an Android SDK.

## Decision

The first executable slice is a pair of Kotlin/JVM modules. `droidproof-model` owns immutable, strongly typed evidence identities and contracts. `droidproof-evidence` validates and writes a bundle. Neither module depends on Android APIs.

JSON is the first persistence format because it is easy to inspect in code review, stable enough for machine processing, and supported by Kotlin serialization without a reporting stack. Version 1 writes `manifest.json` and `timeline.json` with UTF-8, two-space indentation, explicit default values, omitted nulls, and a trailing newline. Hashes and timestamps use canonical lower-case SHA-256 and UTC `Z` forms.

Timeline events are ordered by ascending UTC timestamp. Ties are broken by lexicographic stable event ID. Attribute maps are sorted by key before serialization. These choices make logically identical inputs produce byte-identical JSON regardless of caller collection order.

The manifest has an integer schema version. Writers reject versions they do not support; compatible future readers may support several explicit versions. A schema change that alters semantics or removes a field requires a new version rather than silent interpretation.

## Consequences and limitations

This foundation can be tested quickly on the JVM and consumed by future Android collectors. It intentionally does not yet collect screenshots, semantics, logs, network traffic, or JUnit results; it does not validate an APK signature; and it does not include evidence-file copying, reports, Android integration, or a public command-line interface.
