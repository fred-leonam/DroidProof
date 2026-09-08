# ADR 0003: Read-only Android device capture

## Status

Accepted.

## Context and boundary

The evidence core can ingest and verify files, but a screenshot collector cannot supply the application artifact identity, scenario data, or controlled environment required by its schema-v2 manifest. Inventing those values would turn an observation into an unsupported application-verification claim.

We add only `droidproof-device`, a Kotlin/JVM module using the existing JDK 17 toolchain, catalog, serialization, and JUnit conventions. It depends on `droidproof-model`; its tests may use `droidproof-evidence`. Neither core module depends on it. There is no Android Gradle Plugin, Android API dependency, instrumentation runner, Gradle plugin module, or CLI application.

`CommandRunner`, `AdbOperations`, `DeviceSelector`, and `DeviceCollector` separate subprocess mechanics, fixed ADB operations, device selection, and collection. `CaptureRequest`, `CaptureResult`, `CollectedFile`, and `CollectionIssue` are the integration API. The only device-shell operation accepts an enum of allowlisted properties, and serials and positive PIDs are validated. The capture API exposes no arbitrary device shell commands.

## ADB execution and lifecycle

The explicit `captureDeviceEvidence` JavaExec task resolves installed ADB at execution time, with explicit path, `ANDROID_HOME`, legacy `ANDROID_SDK_ROOT`, then `PATH` precedence. It passes argument lists to `ProcessBuilder`, including executable paths containing spaces. It does not install SDK tools, accept licenses, change settings, restart ADB, or authorize a device. A normal ADB client may start a missing shared server; this adapter never owns or terminates that server.

Two concurrent drain threads keep stdout and stderr separate. Text streams have independent byte caps; binary screenshot stdout streams directly to an owned temporary file. Per-command monotonic deadlines, output limits, nonzero exits, launch failures, read/write failures, and interruption return structured failures. Interruption is restored on the calling thread. Cleanup terminates only the directly launched client, escalating from termination to forced termination with bounded waits, closes its streams, and shuts down its drains. It does not kill arbitrary descendants: a newly started shared ADB server must survive client cleanup.

The device list includes every listed state. More than one entry requires an explicit serial; unauthorized or offline entries are never silently skipped. A supplied serial must match exactly and be in `device` state. Every device command uses `-s <serial>`. Later disconnection is reported as a collection failure. Authorization and device restrictions are respected.

Commands follow the official [Android ADB documentation](https://developer.android.com/tools/adb), including `devices -l`, `-s`, and `exec-out screencap -p`. The official [logcat documentation](https://developer.android.com/tools/logcat) notes device-dependent options and recommends inspecting help. We check `logcat --help` for `--pid`, then issue the bounded snapshot `logcat -d --pid=<positive PID> -v threadtime`. There is no unfiltered fallback.

## Outputs, partial failures, and privacy

Each capture creates an exclusively new directory below the task's `build/droidproof-captures/` root. Injected IDs do not determine directory ownership: a fresh filesystem suffix prevents reuse even when an ID repeats. Final files are never overwritten. The adapter publishes `screenshots/display.png` only after command success and bounded PNG structure, checksum, compressed-stream, and decoding checks. Validation caps input bytes, chunk count, and pixel count. Temporary files are removed on failure; cleanup failures are reported and may leave a named temporary file for local inspection.

Logs are disabled unless the caller opts in and supplies a positive PID. Unsupported, empty, failed, and truncated snapshots are distinct. Truncated/failed logs are not published. A successful screenshot survives log failure, with a partial result and a failing Gradle task exit status. If the screenshot fails the capture is failed, though any other successful outputs remain available. Output-root and collector-document write failures propagate as I/O failures; the adapter does not delete successful files in response.

`capture.json` has its own collector schema version 1 and records selected identity, allowlisted observed fields (including unavailable reasons), host start/end times, file-relative paths, outcomes, issues, and limitations. It never serializes local source paths, raw command stderr, fabricated artifact hashes, signing certificates, random seeds, environment guarantees, or scenario verdicts. It is not an evidence bundle manifest and does not claim bundle verification.

Screenshots can show private data, a protected/blank window, or an unrelated app. PNG validity establishes format validity only. Secure-window restrictions are never bypassed. Metadata and logs can also identify devices or contain private data. Logs are limited to an explicitly supplied PID, but PID reuse, restarts, retained historical records, filtering, and permissions limit attribution and completeness. We do not clear logs, discover unrelated processes, or start/stop apps. Multi-process and continuous collection are deferred. Outputs are ignored build files and are never uploaded as CI artifacts.

## Host timing and evidence integration

An injectable `Clock` and ID source make offline observations testable. Host timestamps describe collection boundaries; they neither control the app clock nor establish a causal order across processes. Real captures can differ byte-for-byte and their host wall clock may be adjusted during collection.

Collected-file descriptors retain local `Path` values in memory alongside safe destinations, media types, and model roles. A future coordinator maps them to the evidence writer's `EvidenceFileInput` after establishing truthful artifact/scenario/environment inputs. An offline integration test uses deterministic fake ADB output and explicitly synthetic manifest values, then verifies all references in the resulting v2 bundle. Schema v2 is unchanged.

Existing SHA-256 checks establish consistency with the supplied manifest, not authenticity or application correctness. Backup-and-rename bundle replacement offers rollback for caught failures; it is not crash-atomic.

## Validation and Gradle policy

The task participates in the repository's configuration cache, with configuration values captured as task arguments and no ADB discovery during configuration. It is never attached to `check`/`test`, never up-to-date, and cannot be restored from build cache. CI continues to run only offline `check`, which now includes device tests. Local JVM child processes exercise actual binary transport, concurrent drains, bounded output, timeout, interruption, launch and exit failures without SDK, network, or devices. Fake ADB capture tests exercise collection and integration without live device data.

Live validation requires an explicitly identified already-authorized test device or emulator. This milestone does not complete Android scenario execution; artifact binding and scenario coordination remain the next boundary.
