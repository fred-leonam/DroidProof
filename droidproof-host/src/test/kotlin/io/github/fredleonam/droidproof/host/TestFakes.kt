package io.github.fredleonam.droidproof.host

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
    var installResult: DeviceCall<Unit> = DeviceCall(Unit)
    var launchResult: DeviceCall<Unit> = DeviceCall(Unit)
    var tapResult: DeviceCall<Unit> = DeviceCall(Unit)
    val dumps = ArrayDeque<DumpResponse>()

    override fun preflight(
        serial: String,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        operations += "preflight:$serial"
        return preflightResult
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

    override fun tap(
        serial: String,
        coordinates: TapCoordinates,
        timeoutMillis: Long,
    ): DeviceCall<Unit> {
        operations += "tap:$serial:${coordinates.x}:${coordinates.y}"
        return tapResult
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
