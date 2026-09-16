package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.model.EmulatorCapabilityObservationV1
import io.github.fredleonam.droidproof.model.EmulatorEnvironmentContractV1
import io.github.fredleonam.droidproof.model.EmulatorEnvironmentState
import io.github.fredleonam.droidproof.model.Orientation
import java.nio.file.Files
import java.nio.file.Path

internal data class DumpResponse(
    val bytes: ByteArray? = null,
    val failure: DeviceFailureKind? = null,
    val detail: String? = null,
)

internal open class FakeSmokeDevice : SmokeDeviceOperations {
    val operations = mutableListOf<String>()
    var installedBytes: List<ByteArray> = emptyList()
    var preflightResult: DeviceCall<Unit> = DeviceCall(Unit)
    var capabilityResult =
        DeviceCall(
            EmulatorCapabilityObservationV1(
                apiLevel = 35,
                buildFingerprint = "generic/sdk",
                bootIdentifier = "123e4567-e89b-12d3-a456-426614174000",
                commandSurfaces = listOf("getprop", "settings", "cmd-locale", "uiautomator-dump"),
            ),
        )

    override fun probeCapabilities(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<EmulatorCapabilityObservationV1> {
        operations += "capabilities:$serial"
        return capabilityResult
    }

    var localeResult = DeviceCall(DeviceLocaleObservation("en-US", "en-US"))
    var orientationResult = DeviceCall(DeviceOrientationObservation(Orientation.PORTRAIT, "accelerometerRotation=0,userRotation=0"))
    var animationsResult = DeviceCall(DeviceAnimationObservations(0.0, 0.0, 0.0))
    var snapshotResult = DeviceCall(EmulatorEnvironmentState("en-US", 0, 0, 0.0, 0.0, 0.0))
    var applyResult: DeviceCall<Unit> = DeviceCall(Unit)
    var restoreResult: DeviceCall<Unit> = DeviceCall(Unit)
    var installResult: DeviceCall<Unit> = DeviceCall(Unit)
    var launchResult: DeviceCall<Unit> = DeviceCall(Unit)
    var reverseResult: DeviceCall<Unit> = DeviceCall(Unit)
    var removeReverseResult: DeviceCall<Unit> = DeviceCall(Unit)
    var tapResult: DeviceCall<Unit> = DeviceCall(Unit)
    var inputTextResult: DeviceCall<Unit> = DeviceCall(Unit)
    var afterOperation: (String) -> Unit = {}
    val dumps = ArrayDeque<DumpResponse>()

    override fun preflight(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        operations += "preflight:$serial"
        return preflightResult
    }

    override fun observeLocale(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<DeviceLocaleObservation> {
        operations += "locale:$serial"
        afterOperation("locale")
        return localeResult
    }

    override fun observeOrientation(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<DeviceOrientationObservation> {
        operations += "orientation:$serial"
        afterOperation("orientation")
        return orientationResult
    }

    override fun observeAnimations(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<DeviceAnimationObservations> {
        operations += "animations:$serial"
        afterOperation("animations")
        return animationsResult
    }

    override fun snapshotEnvironment(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<EmulatorEnvironmentState> {
        operations += "snapshot:$serial"
        return snapshotResult
    }

    override fun applyEnvironment(
        serial: String,
        contract: EmulatorEnvironmentContractV1,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        operations += "apply:$serial"
        return applyResult
    }

    override fun restoreEnvironment(
        serial: String,
        state: EmulatorEnvironmentState,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        operations += "restore:$serial"
        return restoreResult
    }

    override fun packagePaths(
        serial: String,
        packageName: String,
        timeoutMillis: Long,
    ): DeviceCall<InstalledPackagePaths> {
        operations += "paths:$serial:$packageName"
        return DeviceCall(InstalledPackagePaths(installedBytes.indices.map { "/data/app/package/base-$it.apk" }))
    }

    override fun pullApk(
        serial: String,
        remotePath: String,
        destination: Path,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        operations += "pull:$serial:$remotePath"
        val index = remotePath.substringAfterLast('-').substringBefore(".apk").toInt()
        Files.write(destination, installedBytes[index])
        return DeviceCall(Unit)
    }

    override fun installApk(
        serial: String,
        apk: Path,
        replaceExisting: Boolean,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        operations += "install:$serial:$replaceExisting"
        if (!installResult.isSuccessful) return installResult
        installedBytes = listOf(Files.readAllBytes(apk))
        return DeviceCall(Unit)
    }

    override fun launch(
        serial: String,
        component: String,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        operations += "launch:$serial:$component"
        return launchResult
    }

    override fun reverseTcp(
        serial: String,
        devicePort: Int,
        hostPort: Int,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        operations += "reverse:$serial:$devicePort:$hostPort"
        afterOperation("reverse")
        return reverseResult
    }

    override fun removeReverseTcp(
        serial: String,
        devicePort: Int,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        operations += "reverse-remove:$serial:$devicePort"
        afterOperation("reverse-remove")
        return removeReverseResult
    }

    override fun tap(
        serial: String,
        coordinates: TapCoordinates,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        operations += "tap:$serial:${coordinates.x}:${coordinates.y}"
        afterOperation("tap")
        return tapResult
    }

    override fun inputText(
        serial: String,
        text: String,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        operations += "input:$serial:$text"
        afterOperation("input")
        return inputTextResult
    }

    override fun dumpHierarchy(
        serial: String,
        remotePath: String,
        destination: Path,
        timeoutMillis: Long,
        outputLimitBytes: Long,
    ): DeviceCall<Unit> {
        operations += "dump:$serial:$remotePath"
        val response = if (dumps.isEmpty()) DumpResponse(NON_MATCHING_XML) else dumps.removeFirst()
        if (response.failure != null) return DeviceCall(failure = response.failure, detail = response.detail)
        response.bytes?.let { Files.write(destination, it) }
        afterOperation("dump")
        return DeviceCall(Unit)
    }

    internal companion object {
        val MATCHING_XML =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <hierarchy><node package="io.droidproof.smoke" resource-id="io.droidproof.smoke:id/status" text="DroidProof ready"/></hierarchy>
            """.trimIndent().toByteArray()
        val NON_MATCHING_XML =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <hierarchy><node package="io.droidproof.smoke" resource-id="io.droidproof.smoke:id/status" text="Not ready"/></hierarchy>
            """.trimIndent().toByteArray()
    }
}

internal class FakeMonotonicClock(var nanos: Long = 0) : MonotonicClock {
    override fun nanoTime(): Long = nanos

    fun advanceMillis(millis: Long) {
        nanos += java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis)
    }
}
