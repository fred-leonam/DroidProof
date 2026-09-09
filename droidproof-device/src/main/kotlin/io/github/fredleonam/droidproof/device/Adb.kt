package io.github.fredleonam.droidproof.device

import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

object AdbPathResolver {
    fun resolve(
        explicit: String? = null,
        environment: Map<String, String> = System.getenv(),
        windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    ): Path {
        val name = if (windows) "adb.exe" else "adb"

        fun usable(path: Path) = Files.isRegularFile(path) && Files.isExecutable(path)
        if (explicit != null) {
            val path = Path.of(explicit).toAbsolutePath().normalize()
            require(explicit.isNotBlank() && usable(path)) { "ADB executable is unavailable. Check -Pdroidproof.adbPath=$explicit" }
            return path
        }
        val candidates =
            listOfNotNull(environment["ANDROID_HOME"], environment["ANDROID_SDK_ROOT"])
                .filter { it.isNotBlank() }.map { Path.of(it, "platform-tools", name) } +
                environment["PATH"].orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it, name) }
        return candidates.firstOrNull(::usable)?.toAbsolutePath()?.normalize()
            ?: throw IllegalArgumentException(
                "ADB not found. Set -Pdroidproof.adbPath to an installed Platform Tools executable, ANDROID_HOME, or PATH.",
            )
    }
}

@Serializable
data class DeviceIdentity(val serial: String, val state: String, val attributes: Map<String, String> = emptyMap())

data class DeviceSelection(val device: DeviceIdentity? = null, val issue: CollectionIssue? = null)

object DeviceSelector {
    fun parse(text: String): List<DeviceIdentity> =
        text.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && it != "List of devices attached" }
            .map { line ->
                val parts = line.split(Regex("\\s+"))
                DeviceIdentity(
                    parts[0],
                    parts.getOrElse(1) { "unknown" },
                    parts.drop(2).filter { ':' in it }
                        .associate { it.substringBefore(':') to it.substringAfter(':') }.toSortedMap(),
                )
            }.toList()

    fun select(
        devices: List<DeviceIdentity>,
        serial: String?,
    ): DeviceSelection {
        fun failure(
            code: CollectionIssueCode,
            message: String,
        ) = DeviceSelection(issue = CollectionIssue(code, "device", message))
        if (serial == null && devices.isEmpty()) {
            return failure(
                CollectionIssueCode.NO_DEVICES,
                "No devices listed. Connect an already-authorized test device.",
            )
        }
        if (serial == null && devices.size > 1) {
            return failure(
                CollectionIssueCode.AMBIGUOUS_DEVICE,
                "Multiple devices listed; supply droidproof.deviceSerial.",
            )
        }
        val matches = if (serial == null) devices else devices.filter { it.serial == serial }
        if (matches.isEmpty()) {
            return failure(
                CollectionIssueCode.DISCONNECTED,
                "Requested device is not listed; check the exact serial and connection.",
            )
        }
        if (matches.size > 1) return failure(CollectionIssueCode.AMBIGUOUS_DEVICE, "Serial is listed more than once.")
        val device = matches.single()
        return when (device.state) {
            "device" ->
                if (validSerial(
                        device.serial,
                    )
                ) {
                    DeviceSelection(device)
                } else {
                    failure(CollectionIssueCode.UNAVAILABLE, "Device serial is invalid.")
                }
            "unauthorized" -> failure(CollectionIssueCode.UNAUTHORIZED, "Device is unauthorized; use an already-authorized test device.")
            "offline" -> failure(CollectionIssueCode.OFFLINE, "Device is offline; check the test device connection.")
            else -> failure(CollectionIssueCode.UNAVAILABLE, "Device is unavailable in state ${device.state}.")
        }
    }
}

internal fun validSerial(serial: String): Boolean = Regex("[A-Za-z0-9][A-Za-z0-9._:\\[\\]-]{0,255}").matches(serial)

enum class DeviceProperty(val key: String, val field: String) {
    FINGERPRINT("ro.build.fingerprint", "buildFingerprint"),
    API_LEVEL("ro.build.version.sdk", "apiLevel"),
    MANUFACTURER("ro.product.manufacturer", "manufacturer"),
    MODEL("ro.product.model", "model"),
}

interface AdbOperations {
    fun devices(limits: CaptureLimits): CommandResult

    fun property(
        serial: String,
        property: DeviceProperty,
        limits: CaptureLimits,
    ): CommandResult

    fun screenshot(
        serial: String,
        destination: Path,
        limits: CaptureLimits,
    ): CommandResult

    fun logcatHelp(
        serial: String,
        limits: CaptureLimits,
    ): CommandResult

    fun logcat(
        serial: String,
        pid: Int,
        limits: CaptureLimits,
    ): CommandResult
}

class AdbClient(private val executable: Path, private val runner: CommandRunner = ProcessCommandRunner()) : AdbOperations {
    override fun devices(limits: CaptureLimits): CommandResult = run(listOf("devices", "-l"), limits)

    override fun property(
        serial: String,
        property: DeviceProperty,
        limits: CaptureLimits,
    ): CommandResult = run(target(serial) + listOf("shell", "getprop", property.key), limits)

    override fun screenshot(
        serial: String,
        destination: Path,
        limits: CaptureLimits,
    ): CommandResult = run(target(serial) + listOf("exec-out", "screencap", "-p"), limits, limits.screenshotLimitBytes, destination)

    override fun logcatHelp(
        serial: String,
        limits: CaptureLimits,
    ): CommandResult = run(target(serial) + listOf("logcat", "--help"), limits)

    override fun logcat(
        serial: String,
        pid: Int,
        limits: CaptureLimits,
    ): CommandResult {
        require(pid > 0) { "Logcat PID must be positive." }
        return run(target(serial) + listOf("logcat", "-d", "--pid=$pid", "-v", "threadtime"), limits, limits.logcatLimitBytes)
    }

    private fun target(serial: String): List<String> {
        require(validSerial(serial)) { "Invalid device serial." }
        return listOf("-s", serial)
    }

    private fun run(
        args: List<String>,
        limits: CaptureLimits,
        stdoutLimit: Long = limits.textLimitBytes,
        file: Path? = null,
    ) = runner.execute(
        CommandRequest(listOf(executable.toString()) + args, limits.commandTimeoutMillis, stdoutLimit, limits.textLimitBytes, file),
    )
}
