package io.github.fredleonam.droidproof.device

import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.EvidenceFileRole
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

class DeviceCollector(
    private val adb: AdbOperations,
    private val clock: Clock = Clock.systemUTC(),
    private val idSource: () -> String = { UUID.randomUUID().toString() },
) {
    /** Creates a new owned directory. Output-root or capture.json write failures propagate as I/O exceptions. */
    fun capture(request: CaptureRequest): CaptureResult {
        val started = clock.instant().toString()
        val id = idSource()
        require(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}").matches(id)) { "Capture ID must be a safe identifier." }
        Files.createDirectories(request.outputRoot)
        val directory = Files.createTempDirectory(request.outputRoot, "$id-")
        val issues = mutableListOf<CollectionIssue>()
        val files = mutableListOf<CollectedFile>()
        val metadata = DeviceProperty.entries.associate { it.field to ObservedField(reason = "Device was not selected.") }.toMutableMap()
        var screenshot = CollectionOutcome.NOT_COLLECTED
        var logcat = if (request.includeLogcat) CollectionOutcome.NOT_COLLECTED else CollectionOutcome.NOT_REQUESTED
        val listing = adb.devices(request.limits)
        val selection =
            if (listing.failure != null) {
                DeviceSelection(issue = commandIssue(listing, "device"))
            } else {
                DeviceSelector.select(DeviceSelector.parse(listing.stdout), request.serial)
            }
        selection.issue?.let(issues::add)
        selection.device?.let { device ->
            for (property in DeviceProperty.entries) {
                val observed = adb.property(device.serial, property, request.limits)
                val value = observed.stdout.trim()
                val reason =
                    when {
                        observed.failure != null -> {
                            issues += commandIssue(observed, property.field)
                            "Command failed: ${observed.failure}."
                        }
                        value.isEmpty() -> "Property returned no value."
                        property == DeviceProperty.API_LEVEL && (value.toIntOrNull() ?: 0) <= 0 -> "Property was not a positive API level."
                        else -> null
                    }
                metadata[property.field] = if (reason == null) ObservedField(value) else ObservedField(reason = reason)
                if (reason != null) issues += CollectionIssue(CollectionIssueCode.METADATA_UNAVAILABLE, property.field, reason)
            }
            screenshot = collectScreenshot(device.serial, directory, request.limits, files, issues)
            if (request.includeLogcat) {
                logcat = collectLogs(device.serial, requireNotNull(request.pid), directory, request.limits, files, issues)
            }
        }
        val status =
            when {
                screenshot != CollectionOutcome.SUCCESS -> CaptureStatus.FAILED
                issues.isNotEmpty() -> CaptureStatus.PARTIAL
                else -> CaptureStatus.SUCCESS
            }
        val document =
            CaptureDocument(
                captureId = id, hostStartedAt = started, hostEndedAt = clock.instant().toString(),
                device = selection.device, metadata = metadata.toSortedMap(), status = status,
                screenshot = screenshot, logcat = logcat, requestedPid = request.pid,
                files = files.map { CaptureFile(it.destination, it.mediaType, it.role) }, issues = issues,
            )
        val temporary = Files.createTempFile(directory, ".capture-", ".tmp")
        try {
            Files.writeString(temporary, captureJson.encodeToString(document) + "\n")
            Files.move(temporary, directory.resolve("capture.json"))
        } finally {
            Files.deleteIfExists(temporary)
        }
        return CaptureResult(directory, document, files.toList())
    }

    private fun collectScreenshot(
        serial: String,
        directory: Path,
        limits: CaptureLimits,
        files: MutableList<CollectedFile>,
        issues: MutableList<CollectionIssue>,
    ): CollectionOutcome =
        collectFile(directory, "screenshots/display.png", "image/png", EvidenceFileRole.SCREENSHOT, files, issues) { temporary ->
            val result = adb.screenshot(serial, temporary, limits)
            when {
                result.failure != null -> failedCommand(result, "screenshot", issues)
                Files.size(temporary) == 0L -> {
                    issues += CollectionIssue(CollectionIssueCode.EMPTY, "screenshot", "Screenshot returned no bytes.")
                    CollectionOutcome.EMPTY
                }
                !validPng(temporary, limits.screenshotLimitBytes) -> {
                    issues +=
                        CollectionIssue(
                            CollectionIssueCode.INVALID_PNG, "screenshot",
                            "Screenshot is invalid, truncated, or exceeds PNG validation bounds.",
                        )
                    CollectionOutcome.FAILED
                }
                else -> CollectionOutcome.SUCCESS
            }
        }

    private fun collectLogs(
        serial: String,
        pid: Int,
        directory: Path,
        limits: CaptureLimits,
        files: MutableList<CollectedFile>,
        issues: MutableList<CollectionIssue>,
    ): CollectionOutcome {
        val help = adb.logcatHelp(serial, limits)
        if (help.failure != null) return failedCommand(help, "logcat", issues)
        if (!Regex("--pid(?:[=\\s]|$)").containsMatchIn(help.stdout + "\n" + help.stderr)) {
            issues +=
                CollectionIssue(
                    CollectionIssueCode.UNSUPPORTED, "logcat",
                    "Device logcat does not advertise --pid support; no logs collected.",
                )
            return CollectionOutcome.UNSUPPORTED
        }
        return collectFile(directory, "logs/logcat.txt", "text/plain", EvidenceFileRole.LOGCAT, files, issues) { temporary ->
            val result = adb.logcat(serial, pid, limits)
            when {
                result.failure != null -> failedCommand(result, "logcat", issues)
                result.stdout.toByteArray(Charsets.UTF_8).size > limits.logcatLimitBytes ->
                    failedCommand(result.copy(failure = CommandFailure.OUTPUT_LIMIT), "logcat", issues)
                result.stdout.isBlank() -> {
                    issues += CollectionIssue(CollectionIssueCode.EMPTY, "logcat", "PID-filtered snapshot returned no logs.")
                    CollectionOutcome.EMPTY
                }
                else -> {
                    Files.writeString(temporary, result.stdout)
                    CollectionOutcome.SUCCESS
                }
            }
        }
    }

    private fun collectFile(
        directory: Path,
        relative: String,
        mediaType: String,
        role: EvidenceFileRole,
        files: MutableList<CollectedFile>,
        issues: MutableList<CollectionIssue>,
        collect: (Path) -> CollectionOutcome,
    ): CollectionOutcome {
        var temporary: Path? = null
        return try {
            val destination = directory.resolve(relative)
            Files.createDirectories(destination.parent)
            temporary = Files.createTempFile(destination.parent, ".collect-", ".tmp")
            val outcome = collect(temporary)
            if (outcome == CollectionOutcome.SUCCESS) {
                Files.move(temporary, destination)
                files += CollectedFile(destination, BundleRelativePath(relative), mediaType, role)
            }
            outcome
        } catch (_: IOException) {
            issues += CollectionIssue(CollectionIssueCode.IO, relative, "Could not read or publish the collected file.")
            CollectionOutcome.FAILED
        } catch (_: SecurityException) {
            issues += CollectionIssue(CollectionIssueCode.IO, relative, "Access to the collected file was denied.")
            CollectionOutcome.FAILED
        } finally {
            temporary?.let { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (_: IOException) {
                    issues +=
                        CollectionIssue(
                            CollectionIssueCode.IO, relative,
                            "Could not remove a partial temporary file; inspect the capture directory.",
                        )
                }
            }
        }
    }

    private fun failedCommand(
        result: CommandResult,
        component: String,
        issues: MutableList<CollectionIssue>,
    ): CollectionOutcome {
        issues += commandIssue(result, component)
        return if (result.failure == CommandFailure.OUTPUT_LIMIT) CollectionOutcome.TRUNCATED else CollectionOutcome.FAILED
    }

    private fun commandIssue(
        result: CommandResult,
        component: String,
    ): CollectionIssue {
        val code =
            when {
                result.failure == CommandFailure.NONZERO_EXIT && "unauthorized" in result.stderr -> CollectionIssueCode.UNAUTHORIZED
                result.failure == CommandFailure.NONZERO_EXIT && "offline" in result.stderr -> CollectionIssueCode.OFFLINE
                result.failure == CommandFailure.NONZERO_EXIT &&
                    (
                        Regex("device (?:'[^']+' )?not found").containsMatchIn(result.stderr) ||
                            listOf("device disconnected", "no devices/emulators found", "error: closed").any { it in result.stderr }
                    )
                -> CollectionIssueCode.DISCONNECTED
                else -> CollectionIssueCode.valueOf(requireNotNull(result.failure).name)
            }
        // Do not persist raw stderr: it can contain host paths or private device data.
        return CollectionIssue(
            code,
            component,
            "$component command failed: $code (exit ${result.exitCode ?: "unavailable"}). " +
                "Check ADB availability and the test device connection.",
        )
    }
}

private val captureJson =
    Json {
        prettyPrint = true
        encodeDefaults = true
    }
