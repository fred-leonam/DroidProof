package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.model.EnvironmentExecutionMode
import java.nio.file.Path

enum class EmulatorBackend { LEGACY, ANDROID_CLI }

/** The single validated boundary between Gradle properties and the smoke runner. */
data class RunSmokeScenarioConfiguration(
    val outputRoot: Path,
    val apkPath: Path,
    val scenarioPath: Path,
    val deviceSerial: String?,
    val adbPath: String?,
    val replaceExisting: Boolean,
    val signingPrivateKeyPath: Path?,
    val signingPublicKeyPath: Path?,
    val environmentPath: Path?,
    val environmentMode: EnvironmentExecutionMode,
    val recoveryStateRoot: Path,
    val avdName: String?,
    val emulatorPath: Path,
    val emulatorPort: Int,
    val lifecycleStartupTimeoutMillis: Long,
    val lifecycleShutdownTimeoutMillis: Long,
    val provisioningPath: Path?,
    val sdkRoot: Path?,
    val provisioningStateRoot: Path,
    val avdManagerPath: Path,
    val version: String,
    val emulatorBackend: EmulatorBackend,
    val androidCliPath: Path?,
) {
    init {
        require(listOf(deviceSerial, avdName, provisioningPath).count { it != null } == 1) {
            "Set exactly one of droidproof.deviceSerial, droidproof.avdName, or droidproof.provisioningPath."
        }
        require(deviceSerial == null || Regex("[A-Za-z0-9._:-]{1,128}").matches(deviceSerial)) { "droidproof.deviceSerial is invalid." }
        require(avdName == null || Regex("[A-Za-z0-9._-]{1,128}").matches(avdName)) { "droidproof.avdName is invalid." }
        require((signingPrivateKeyPath == null) == (signingPublicKeyPath == null)) {
            "droidproof.signingPrivateKeyPath and droidproof.signingPublicKeyPath must be supplied together."
        }
        require(emulatorPort in 5554..5682 && emulatorPort % 2 == 0) {
            "droidproof.emulatorPort must be an even emulator port from 5554 through 5682."
        }
        require(lifecycleStartupTimeoutMillis in 1..3_600_000 && lifecycleShutdownTimeoutMillis in 1..3_600_000) {
            "Lifecycle timeouts must be from 1 through 3600000 milliseconds."
        }
        require(version.isNotBlank() && version.length <= 128) {
            "Project version is invalid."
        }
        if (provisioningPath != null) {
            require(sdkRoot != null) { "droidproof.sdkRoot is required for provisioning." }
        }
        if (emulatorBackend == EmulatorBackend.ANDROID_CLI && provisioningPath != null) {
            require(androidCliPath != null) {
                "droidproof.androidCliPath is required for android-cli provisioning."
            }
        }
    }

    companion object {
        private val keys =
            setOf(
                "outputRoot", "apkPath", "scenarioPath", "deviceSerial", "adbPath", "replaceExisting",
                "signingPrivateKeyPath", "signingPublicKeyPath", "environmentPath", "environmentMode",
                "recoveryStateRoot", "avdName", "emulatorPath", "emulatorPort", "lifecycleStartupTimeoutMillis",
                "lifecycleShutdownTimeoutMillis", "provisioningPath", "sdkRoot", "provisioningStateRoot",
                "avdManagerPath", "version", "emulatorBackend", "androidCliPath",
            )

        fun parse(arguments: Array<String>): RunSmokeScenarioConfiguration {
            val values =
                arguments.associate { argument ->
                    require(argument.startsWith("--") && argument.contains('=')) {
                        "Expected named --key=value smoke-scenario arguments."
                    }
                    val (key, value) = argument.removePrefix("--").split('=', limit = 2)
                    require(key in keys) { "Unrecognized smoke-scenario option: $key." }
                    key to value
                }
            require(values.size == arguments.size) { "Duplicate smoke-scenario options are not allowed." }

            fun required(key: String) = requireNotNull(values[key]?.takeIf(String::isNotBlank)) { "droidproof.$key is required." }

            fun optional(key: String) = values[key]?.takeIf(String::isNotBlank)

            fun number(key: String) = required(key).toLongOrNull() ?: throw IllegalArgumentException("droidproof.$key must be a number.")
            return RunSmokeScenarioConfiguration(
                Path.of(
                    required("outputRoot"),
                ),
                Path.of(required("apkPath")), Path.of(required("scenarioPath")), optional("deviceSerial"), optional("adbPath"),
                required("replaceExisting").toBooleanStrictOrNull()
                    ?: throw IllegalArgumentException("droidproof.replaceExisting must be true or false."),
                optional(
                    "signingPrivateKeyPath",
                )?.let(Path::of),
                optional("signingPublicKeyPath")?.let(Path::of), optional("environmentPath")?.let(Path::of),
                runCatching {
                    EnvironmentExecutionMode.valueOf(required("environmentMode"))
                }.getOrElse { throw IllegalArgumentException("droidproof.environmentMode must be VERIFY_ONLY or APPLY_AND_RESTORE.") },
                Path.of(
                    required("recoveryStateRoot"),
                ),
                optional(
                    "avdName",
                ),
                Path.of(
                    required("emulatorPath"),
                ),
                number("emulatorPort").toInt(), number("lifecycleStartupTimeoutMillis"), number("lifecycleShutdownTimeoutMillis"),
                optional(
                    "provisioningPath",
                )?.let(
                    Path::of,
                ),
                optional(
                    "sdkRoot",
                )?.let(Path::of),
                Path.of(required("provisioningStateRoot")), Path.of(required("avdManagerPath")), required("version"),
                when (required("emulatorBackend")) {
                    "legacy" -> EmulatorBackend.LEGACY
                    "android-cli" -> EmulatorBackend.ANDROID_CLI
                    else -> throw IllegalArgumentException("droidproof.emulatorBackend must be legacy or android-cli.")
                },
                optional("androidCliPath")?.let(Path::of),
            )
        }
    }
}
