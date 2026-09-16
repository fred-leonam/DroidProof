package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CommandFailure
import io.github.fredleonam.droidproof.device.CommandRequest
import io.github.fredleonam.droidproof.device.CommandResult
import io.github.fredleonam.droidproof.device.CommandRunner
import io.github.fredleonam.droidproof.device.DeviceSelector
import io.github.fredleonam.droidproof.device.ProcessCommandRunner
import io.github.fredleonam.droidproof.model.EmulatorEnvironmentState
import io.github.fredleonam.droidproof.model.Orientation
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit

enum class DeviceFailureKind {
    COMMAND,
    DISCONNECTED,
    CANCELLED,
    TIMEOUT,
    INVALID_OUTPUT,
    UNSUPPORTED,
}

data class DeviceCall<T>(
    val value: T? = null,
    val failure: DeviceFailureKind? = null,
    val detail: String? = null,
) {
    val isSuccessful: Boolean get() = failure == null
}

data class InstalledPackagePaths(val paths: List<String>)

data class DeviceLocaleObservation(val normalized: String, val rawSafe: String)

data class DeviceOrientationObservation(val orientation: Orientation, val rawSafe: String)

data class DeviceAnimationObservations(
    val windowScale: Double,
    val transitionScale: Double,
    val animatorScale: Double,
)

interface SmokeDeviceOperations {
    fun preflight(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<Unit>

    fun observeLocale(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<DeviceLocaleObservation>

    fun observeOrientation(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<DeviceOrientationObservation>

    fun observeAnimations(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<DeviceAnimationObservations>

    fun snapshotEnvironment(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<EmulatorEnvironmentState> =
        DeviceCall(failure = DeviceFailureKind.UNSUPPORTED, detail = "Environment mutation is unsupported by this device implementation.")

    fun applyEnvironment(
        serial: String,
        contract: io.github.fredleonam.droidproof.model.EmulatorEnvironmentContractV1,
        timeoutMillis: Long,
    ): DeviceCall<Unit> =
        DeviceCall(failure = DeviceFailureKind.UNSUPPORTED, detail = "Environment mutation is unsupported by this device implementation.")

    fun restoreEnvironment(
        serial: String,
        state: EmulatorEnvironmentState,
        timeoutMillis: Long,
    ): DeviceCall<Unit> =
        DeviceCall(
            failure = DeviceFailureKind.UNSUPPORTED,
            detail = "Environment restoration is unsupported by this device implementation.",
        )

    fun packagePaths(
        serial: String,
        packageName: String,
        timeoutMillis: Long,
    ): DeviceCall<InstalledPackagePaths>

    fun pullApk(
        serial: String,
        remotePath: String,
        destination: Path,
        timeoutMillis: Long,
    ): DeviceCall<Unit>

    fun installApk(
        serial: String,
        apk: Path,
        replaceExisting: Boolean,
        timeoutMillis: Long,
    ): DeviceCall<Unit>

    fun launch(
        serial: String,
        component: String,
        timeoutMillis: Long,
    ): DeviceCall<Unit>

    fun reverseTcp(
        serial: String,
        devicePort: Int,
        hostPort: Int,
        timeoutMillis: Long,
    ): DeviceCall<Unit>

    fun removeReverseTcp(
        serial: String,
        devicePort: Int,
        timeoutMillis: Long,
    ): DeviceCall<Unit>

    fun tap(
        serial: String,
        coordinates: TapCoordinates,
        timeoutMillis: Long,
    ): DeviceCall<Unit>

    fun inputText(
        serial: String,
        text: String,
        timeoutMillis: Long,
    ): DeviceCall<Unit>

    fun dumpHierarchy(
        serial: String,
        remotePath: String,
        destination: Path,
        timeoutMillis: Long,
        outputLimitBytes: Long,
    ): DeviceCall<Unit>
}

class SmokeAdbClient(
    private val executable: Path,
    private val runner: CommandRunner = ProcessCommandRunner(),
) : SmokeDeviceOperations {
    override fun snapshotEnvironment(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<EmulatorEnvironmentState> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val locale = observeLocale(serial, remainingMillis(deadline))
        if (!locale.isSuccessful) return DeviceCall(failure = locale.failure, detail = locale.detail)

        fun setting(
            namespace: String,
            name: String,
        ): DeviceCall<String> {
            val result =
                run(
                    target(serial) + listOf("shell", "settings", "--user", "0", "get", namespace, name),
                    remainingMillis(deadline),
                    SETTING_LIMIT_BYTES,
                )
            val value = if (result.failure == null) singleSettingValue(result) else null
            return if (value != null) {
                DeviceCall(
                    value,
                )
            } else {
                DeviceCall(
                    failure =
                        if (result.failure == null) {
                            DeviceFailureKind.INVALID_OUTPUT
                        } else {
                            result.failureCall<DeviceCall<String>>(
                                "",
                            ).failure!!
                        },
                    detail = "Environment snapshot output was unsupported.",
                )
            }
        }
        val values = mutableListOf<String>()
        for ((namespace, name) in listOf(
            "system" to "accelerometer_rotation",
            "system" to "user_rotation",
            "global" to ANIMATION_SETTINGS[0],
            "global" to ANIMATION_SETTINGS[1],
            "global" to ANIMATION_SETTINGS[2],
        )) {
            val value = setting(namespace, name)
            if (!value.isSuccessful) return DeviceCall(failure = value.failure, detail = value.detail)
            values += requireNotNull(value.value)
        }
        return try {
            DeviceCall(
                EmulatorEnvironmentState(
                    requireNotNull(locale.value).normalized,
                    values[0].toIntOrNull() ?: -1,
                    values[1].toIntOrNull() ?: -1,
                    values[2].toDoubleOrNull() ?: -1.0,
                    values[3].toDoubleOrNull() ?: -1.0,
                    values[4].toDoubleOrNull() ?: -1.0,
                ),
            )
        } catch (
            _: IllegalArgumentException,
        ) {
            DeviceCall(failure = DeviceFailureKind.INVALID_OUTPUT, detail = "Environment snapshot values were unsupported.")
        }
    }

    override fun applyEnvironment(
        serial: String,
        contract: io.github.fredleonam.droidproof.model.EmulatorEnvironmentContractV1,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        // cmd locale is intentionally the only locale mutation mechanism in this narrow emulator slice.
        val commands =
            listOf(
                listOf("shell", "cmd", "locale", "set", contract.locale),
                listOf("shell", "settings", "--user", "0", "put", "system", "accelerometer_rotation", "0"),
                listOf(
                    "shell",
                    "settings",
                    "--user",
                    "0",
                    "put",
                    "system",
                    "user_rotation",
                    if (contract.orientation == Orientation.PORTRAIT) "0" else "1",
                ),
                listOf(
                    "shell",
                    "settings",
                    "--user",
                    "0",
                    "put",
                    "global",
                    ANIMATION_SETTINGS[0],
                    contract.animations.windowScale.toString(),
                ),
                listOf(
                    "shell",
                    "settings",
                    "--user",
                    "0",
                    "put",
                    "global",
                    ANIMATION_SETTINGS[1],
                    contract.animations.transitionScale.toString(),
                ),
                listOf(
                    "shell",
                    "settings",
                    "--user",
                    "0",
                    "put",
                    "global",
                    ANIMATION_SETTINGS[2],
                    contract.animations.animatorScale.toString(),
                ),
            )
        return mutate(serial, commands, deadline, "Requested environment mutation is unavailable on this emulator.")
    }

    override fun restoreEnvironment(
        serial: String,
        state: EmulatorEnvironmentState,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val commands =
            listOf(
                listOf("shell", "cmd", "locale", "set", state.locale),
                listOf(
                    "shell",
                    "settings",
                    "--user",
                    "0",
                    "put",
                    "system",
                    "accelerometer_rotation",
                    state.accelerometerRotation.toString(),
                ),
                listOf("shell", "settings", "--user", "0", "put", "system", "user_rotation", state.userRotation.toString()),
                listOf("shell", "settings", "--user", "0", "put", "global", ANIMATION_SETTINGS[0], state.windowScale.toString()),
                listOf("shell", "settings", "--user", "0", "put", "global", ANIMATION_SETTINGS[1], state.transitionScale.toString()),
                listOf("shell", "settings", "--user", "0", "put", "global", ANIMATION_SETTINGS[2], state.animatorScale.toString()),
            )
        return mutate(serial, commands, deadline, "Environment restoration is unavailable on this emulator.")
    }

    private fun mutate(
        serial: String,
        commands: List<List<String>>,
        deadline: Long,
        detail: String,
    ): DeviceCall<Unit> {
        for (command in commands) {
            val result = run(target(serial) + command, remainingMillis(deadline), SETTING_LIMIT_BYTES)
            if (result.failure != null || result.stderr.isNotBlank()) {
                return DeviceCall(
                    failure =
                        if (result.failure == null) {
                            DeviceFailureKind.UNSUPPORTED
                        } else {
                            result.failureCall<DeviceCall<Unit>>(
                                "",
                            ).failure!!
                        },
                    detail = detail,
                )
            }
        }
        return DeviceCall(Unit)
    }

    override fun preflight(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val listing = run(listOf("devices", "-l"), remainingMillis(deadline))
        if (listing.failure != null) return listing.failureCall("Could not list ADB devices.")
        val selection = DeviceSelector.select(DeviceSelector.parse(listing.stdout), serial)
        if (selection.device == null) return DeviceCall(failure = DeviceFailureKind.DISCONNECTED, detail = selection.issue?.message)

        val emulator = run(target(serial) + listOf("shell", "getprop", "ro.kernel.qemu"), remainingMillis(deadline))
        if (emulator.failure != null) return emulator.failureCall("Could not confirm emulator scope.")
        if (emulator.stdout.trim() != "1") {
            return DeviceCall(failure = DeviceFailureKind.UNSUPPORTED, detail = "Selected serial is not reported as an emulator.")
        }
        val user = run(target(serial) + listOf("shell", "am", "get-current-user"), remainingMillis(deadline))
        if (user.failure != null) return user.failureCall("Could not inspect the active Android user.")
        if (user.stdout.trim() != "0") {
            return DeviceCall(failure = DeviceFailureKind.UNSUPPORTED, detail = "Only emulator primary user 0 is supported.")
        }
        val capability = run(target(serial) + listOf("shell", "uiautomator", "help"), remainingMillis(deadline))
        if (capability.failure != null || "dump" !in (capability.stdout + capability.stderr)) {
            return DeviceCall(failure = DeviceFailureKind.UNSUPPORTED, detail = "uiautomator dump is unavailable.")
        }
        return DeviceCall(Unit)
    }

    override fun observeLocale(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<DeviceLocaleObservation> {
        val result = run(target(serial) + listOf("shell", "getprop", "persist.sys.locale"), timeoutMillis, SETTING_LIMIT_BYTES)
        if (result.failure != null) return result.failureCall("Device locale observation was unavailable.")
        if (result.stderr.isNotBlank()) return invalidObservation("Device locale output was unsupported.")
        val raw = singleSafeLine(result.stdout) ?: return invalidObservation("Device locale output was unsupported.")
        val normalized = canonicalLocale(raw) ?: return invalidObservation("Device locale output was unsupported.")
        return DeviceCall(DeviceLocaleObservation(normalized, raw))
    }

    override fun observeOrientation(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<DeviceOrientationObservation> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val automatic =
            run(
                target(serial) + listOf("shell", "settings", "--user", "0", "get", "system", "accelerometer_rotation"),
                remainingMillis(deadline),
                SETTING_LIMIT_BYTES,
            )
        if (automatic.failure != null) return automatic.failureCall("Device orientation observation was unavailable.")
        val auto = singleSettingValue(automatic) ?: return invalidObservation("Device orientation output was unsupported.")
        if (auto != "0") {
            return DeviceCall(
                failure = DeviceFailureKind.UNSUPPORTED,
                detail = "Auto-rotation must be disabled to verify configured orientation.",
            )
        }
        val configured =
            run(
                target(serial) + listOf("shell", "settings", "--user", "0", "get", "system", "user_rotation"),
                remainingMillis(deadline),
                SETTING_LIMIT_BYTES,
            )
        if (configured.failure != null) return configured.failureCall("Device orientation observation was unavailable.")
        val rotation = singleSettingValue(configured) ?: return invalidObservation("Device orientation output was unsupported.")
        val orientation =
            when (rotation) {
                "0", "2" -> Orientation.PORTRAIT
                "1", "3" -> Orientation.LANDSCAPE
                else -> return invalidObservation("Device orientation output was unsupported.")
            }
        return DeviceCall(DeviceOrientationObservation(orientation, "accelerometerRotation=0,userRotation=$rotation"))
    }

    override fun observeAnimations(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<DeviceAnimationObservations> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val values = mutableListOf<Double>()
        for (setting in ANIMATION_SETTINGS) {
            val result =
                run(
                    target(serial) + listOf("shell", "settings", "get", "global", setting),
                    remainingMillis(deadline),
                    SETTING_LIMIT_BYTES,
                )
            if (result.failure != null) return result.failureCall("Animation-scale observation was unavailable.")
            val raw = singleSettingValue(result) ?: return invalidObservation("Animation-scale output was unsupported.")
            val value = raw.toDoubleOrNull()
            if (value == null || !value.isFinite() || value < 0.0) {
                return invalidObservation("Animation-scale output was unsupported.")
            }
            values += value
        }
        return DeviceCall(DeviceAnimationObservations(values[0], values[1], values[2]))
    }

    override fun packagePaths(
        serial: String,
        packageName: String,
        timeoutMillis: Long,
    ): DeviceCall<InstalledPackagePaths> {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid package name." }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val listing =
            run(
                target(serial) + listOf("shell", "pm", "list", "packages", "--user", "0", packageName),
                remainingMillis(deadline),
            )
        if (listing.failure != null) return listing.failureCall("Could not query installed packages.")
        val installed = listing.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (installed.isEmpty()) return DeviceCall(InstalledPackagePaths(emptyList()))
        if (installed != listOf("package:$packageName")) {
            return DeviceCall(failure = DeviceFailureKind.INVALID_OUTPUT, detail = "Package manager returned unexpected output.")
        }
        // Preflight already requires primary user 0; `pm path --user 0` is not portable across supported Android builds.
        val result = run(target(serial) + listOf("shell", "pm", "path", packageName), remainingMillis(deadline))
        if (result.failure != null) return result.failureCall("Could not query installed APK paths.")
        val lines = result.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) return DeviceCall(InstalledPackagePaths(emptyList()))
        val paths = mutableListOf<String>()
        for (line in lines) {
            if (!line.startsWith("package:")) {
                return DeviceCall(failure = DeviceFailureKind.INVALID_OUTPUT, detail = "Package manager returned unexpected output.")
            }
            val path = line.removePrefix("package:")
            if (!validRemoteApkPath(path)) {
                return DeviceCall(failure = DeviceFailureKind.INVALID_OUTPUT, detail = "Package manager returned an unsafe APK path.")
            }
            paths += path
        }
        return DeviceCall(InstalledPackagePaths(paths))
    }

    override fun pullApk(
        serial: String,
        remotePath: String,
        destination: Path,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        require(validRemoteApkPath(remotePath)) { "Invalid remote APK path." }
        Files.deleteIfExists(destination)
        val result = run(target(serial) + listOf("pull", remotePath, destination.toString()), timeoutMillis)
        if (result.failure != null) return result.failureCall("Could not retrieve installed APK bytes.")
        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(destination)) {
            return DeviceCall(failure = DeviceFailureKind.INVALID_OUTPUT, detail = "ADB pull did not create a fresh regular file.")
        }
        return DeviceCall(Unit)
    }

    override fun installApk(
        serial: String,
        apk: Path,
        replaceExisting: Boolean,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        val options = if (replaceExisting) listOf("-r") else emptyList()
        val result = run(target(serial) + listOf("install") + options + apk.toString(), timeoutMillis)
        if (result.failure != null) return result.failureCall("APK installation failed.")
        val output = result.stdout + "\n" + result.stderr
        if (!output.lineSequence().any { it.trim() == "Success" } || INSTALL_ERROR.containsMatchIn(output)) {
            return DeviceCall(failure = DeviceFailureKind.INVALID_OUTPUT, detail = "ADB did not report a successful installation.")
        }
        return DeviceCall(Unit)
    }

    override fun launch(
        serial: String,
        component: String,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        require(COMPONENT.matches(component)) { "Invalid launch component." }
        val result = run(target(serial) + listOf("shell", "am", "start", "-W", "-n", component), timeoutMillis)
        if (result.failure != null) return result.failureCall("Activity launch command failed.")
        val output = result.stdout + "\n" + result.stderr
        val success = output.lineSequence().any { it.trim() == "Status: ok" || it.trim().startsWith("Starting: Intent") }
        if (!success || ACTIVITY_ERROR.containsMatchIn(output)) {
            return DeviceCall(failure = DeviceFailureKind.INVALID_OUTPUT, detail = "Activity manager reported a launch error.")
        }
        return DeviceCall(Unit)
    }

    override fun reverseTcp(
        serial: String,
        devicePort: Int,
        hostPort: Int,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        validatePort(devicePort)
        validatePort(hostPort)
        val result = run(target(serial) + listOf("reverse", "tcp:$devicePort", "tcp:$hostPort"), timeoutMillis)
        if (result.failure != null) return result.failureCall("ADB reverse setup failed.")
        val standardOutput = result.stdout.trim()
        if (standardOutput !in setOf("", devicePort.toString()) || result.stderr.isNotBlank()) {
            return DeviceCall(failure = DeviceFailureKind.INVALID_OUTPUT, detail = "ADB reverse setup returned unexpected output.")
        }
        return DeviceCall(Unit)
    }

    override fun removeReverseTcp(
        serial: String,
        devicePort: Int,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        validatePort(devicePort)
        val result = run(target(serial) + listOf("reverse", "--remove", "tcp:$devicePort"), timeoutMillis)
        if (result.failure != null) return result.failureCall("ADB reverse cleanup failed.")
        if (result.stdout.isNotBlank() || result.stderr.isNotBlank()) {
            return DeviceCall(failure = DeviceFailureKind.INVALID_OUTPUT, detail = "ADB reverse cleanup returned unexpected output.")
        }
        return DeviceCall(Unit)
    }

    override fun tap(
        serial: String,
        coordinates: TapCoordinates,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        val result =
            run(
                target(serial) + listOf("shell", "input", "tap", coordinates.x.toString(), coordinates.y.toString()),
                timeoutMillis,
            )
        if (result.failure != null) return result.failureCall("UI tap command failed.")
        if (result.stdout.isNotBlank() || result.stderr.isNotBlank()) {
            return DeviceCall(failure = DeviceFailureKind.INVALID_OUTPUT, detail = "UI tap command returned unexpected output.")
        }
        return DeviceCall(Unit)
    }

    override fun inputText(
        serial: String,
        text: String,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        validateInputText(text)
        require(timeoutMillis in 1..3_600_000) { "Command timeout is outside supported bounds." }
        val result = run(target(serial) + listOf("shell", "input", "text", text), timeoutMillis)
        if (result.failure != null) return result.failureCall("UI text input command failed.")
        if (result.stdout.isNotBlank() || result.stderr.isNotBlank()) {
            return DeviceCall(failure = DeviceFailureKind.INVALID_OUTPUT, detail = "UI text input command returned unexpected output.")
        }
        return DeviceCall(Unit)
    }

    override fun dumpHierarchy(
        serial: String,
        remotePath: String,
        destination: Path,
        timeoutMillis: Long,
        outputLimitBytes: Long,
    ): DeviceCall<Unit> {
        require(OWNED_DUMP_PATH.matches(remotePath)) { "Remote hierarchy path is not owned by DroidProof." }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        Files.deleteIfExists(destination)
        val dump =
            run(
                target(serial) + listOf("shell", "uiautomator", "dump", "--compressed", remotePath),
                remainingMillis(deadline),
            )
        if (dump.failure != null) return dump.failureCall("UI hierarchy dump failed.")
        if (!DUMP_SUCCESS.containsMatchIn(dump.stdout + dump.stderr)) {
            return DeviceCall(failure = DeviceFailureKind.INVALID_OUTPUT, detail = "uiautomator did not report a fresh hierarchy.")
        }
        val fetch =
            run(
                target(serial) + listOf("exec-out", "cat", remotePath),
                remainingMillis(deadline),
                outputLimitBytes,
                destination,
            )
        if (fetch.failure != null) {
            Files.deleteIfExists(destination)
            return fetch.failureCall("UI hierarchy retrieval failed.")
        }
        val remove = run(target(serial) + listOf("shell", "rm", "-f", remotePath), remainingMillis(deadline))
        if (remove.failure != null) {
            Files.deleteIfExists(destination)
            return remove.failureCall("Owned UI hierarchy cleanup failed.")
        }
        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || Files.size(destination) == 0L) {
            Files.deleteIfExists(destination)
            return DeviceCall(failure = DeviceFailureKind.INVALID_OUTPUT, detail = "No fresh UI hierarchy bytes were retrieved.")
        }
        return DeviceCall(Unit)
    }

    private fun target(serial: String): List<String> {
        require(DEVICE_SERIAL.matches(serial)) { "Invalid device serial." }
        return listOf("-s", serial)
    }

    private fun remainingMillis(deadlineNanos: Long): Long =
        maxOf(
            1,
            TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()),
        )

    private fun run(
        arguments: List<String>,
        timeoutMillis: Long,
        stdoutLimitBytes: Long = TEXT_LIMIT_BYTES,
        stdoutFile: Path? = null,
    ): CommandResult =
        runner.execute(
            CommandRequest(
                listOf(executable.toString()) + arguments,
                timeoutMillis,
                stdoutLimitBytes,
                TEXT_LIMIT_BYTES,
                stdoutFile,
            ),
        )
}

private fun validatePort(port: Int) {
    require(port in 1..65535) { "TCP port must be between 1 and 65535." }
}

private fun <T> CommandResult.failureCall(detail: String): DeviceCall<T> =
    DeviceCall(
        failure =
            when {
                failure == CommandFailure.INTERRUPTED -> DeviceFailureKind.CANCELLED
                failure == CommandFailure.TIMEOUT -> DeviceFailureKind.TIMEOUT
                "offline" in stderr || "not found" in stderr || "disconnected" in stderr -> DeviceFailureKind.DISCONNECTED
                else -> DeviceFailureKind.COMMAND
            },
        detail = detail,
    )

private fun <T> invalidObservation(detail: String): DeviceCall<T> = DeviceCall(failure = DeviceFailureKind.INVALID_OUTPUT, detail = detail)

private fun singleSettingValue(result: CommandResult): String? = if (result.stderr.isBlank()) singleSafeLine(result.stdout) else null

private fun singleSafeLine(output: String): String? {
    if (output.toByteArray(Charsets.UTF_8).size > SETTING_LIMIT_BYTES) return null
    val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    return lines.singleOrNull()?.takeIf { line ->
        line.length <= 128 && line.all { it.code in 0x21..0x7e }
    }
}

private fun canonicalLocale(value: String): String? {
    if (value == "null") return null
    val locale =
        try {
            java.util.Locale.Builder().setLanguageTag(value.replace('_', '-')).build()
        } catch (_: java.util.IllformedLocaleException) {
            return null
        }
    return locale.toLanguageTag().takeIf { locale.language.isNotBlank() && locale.language != "und" }
}

private fun validRemoteApkPath(path: String): Boolean =
    path.length in 2..4096 &&
        path.startsWith('/') &&
        path.endsWith(".apk") &&
        '\u0000' !in path &&
        '\n' !in path &&
        '\r' !in path &&
        path.split('/').none { it == "." || it == ".." }

private const val TEXT_LIMIT_BYTES = 65_536L
private const val SETTING_LIMIT_BYTES = 1024L
private val ANIMATION_SETTINGS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
private val PACKAGE_NAME = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+")
private val COMPONENT = Regex("[a-zA-Z][a-zA-Z0-9_.]*/[a-zA-Z][a-zA-Z0-9_.]*")
private val OWNED_DUMP_PATH = Regex("/sdcard/Download/droidproof-[A-Za-z0-9_-]{1,128}\\.xml")
private val INSTALL_ERROR = Regex("(?im)^(Failure|Error|Exception)")
private val ACTIVITY_ERROR = Regex("(?im)(^Error:|Exception|SecurityException|does not exist|unable to resolve)")
private val DUMP_SUCCESS = Regex("(?i)UI hier(?:archy|chary) dumped to:")
