package io.github.fredleonam.droidproof.host

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactBinderTest {
    @TempDir
    lateinit var directory: Path
    private val clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `snapshots and hashes the exact owned APK bytes`() {
        val source = apk()

        val artifact = ArtifactBinder(FakeSmokeDevice(), clock).snapshot(source, directory.resolve("work"))

        assertEquals("9379cfb95416438572c33e2c2e03f1fdbdb10e4668cfbdb7bbd0e3049463ac33", artifact.sha256.value)
        Files.writeString(source, "changed")
        assertEquals("apk bytes", Files.readString(artifact.path))
    }

    @Test
    fun `installs absent package and reuses matching existing installation`() {
        val artifact = artifact()
        val absent = FakeSmokeDevice()

        val installed = binder(absent).bind(artifact, SERIAL, PACKAGE, false) { 1000 }
        val matching = FakeSmokeDevice().apply { installedBytes = listOf(Files.readAllBytes(artifact.path)) }
        val reused = binder(matching).bind(artifact, SERIAL, PACKAGE, false) { 1000 }

        assertNull(installed.error)
        assertEquals(InstallationAction.INSTALLED_ABSENT_PACKAGE, installed.state?.action)
        assertTrue(absent.operations.any { it == "install:$SERIAL:false" })
        assertEquals(InstallationAction.REUSED_MATCHING_INSTALLATION, reused.state?.action)
        assertTrue(matching.operations.none { it.startsWith("install:") })
    }

    @Test
    fun `refuses different installation unless replacement is explicit`() {
        val artifact = artifact()
        val refusedDevice = FakeSmokeDevice().apply { installedBytes = listOf("other".toByteArray()) }
        val refused = binder(refusedDevice).bind(artifact, SERIAL, PACKAGE, false) { 1000 }
        val replacementDevice = FakeSmokeDevice().apply { installedBytes = listOf("other".toByteArray()) }
        val replaced = binder(replacementDevice).bind(artifact, SERIAL, PACKAGE, true) { 1000 }

        assertEquals(InstallationAction.REFUSED_DIFFERENT_INSTALLATION, refused.document.action)
        assertTrue(refused.error?.contains("differ") == true)
        assertTrue(refusedDevice.operations.none { it.startsWith("install:") })
        assertEquals(InstallationAction.REPLACED_EXISTING_INSTALLATION, replaced.state?.action)
        assertTrue(replacementDevice.operations.any { it == "install:$SERIAL:true" })
    }

    @Test
    fun `reports install failure split installation and failed final identity`() {
        val artifact = artifact()
        val installFailure =
            FakeSmokeDevice().apply {
                installResult = DeviceCall(failure = DeviceFailureKind.COMMAND, detail = "install failed")
            }
        val failed = binder(installFailure).bind(artifact, SERIAL, PACKAGE, false) { 1000 }
        val split = FakeSmokeDevice().apply { installedBytes = listOf("one".toByteArray(), "two".toByteArray()) }
        val splitResult = binder(split).bind(artifact, SERIAL, PACKAGE, false) { 1000 }
        val changing = FakeSmokeDevice().apply { installedBytes = listOf(Files.readAllBytes(artifact.path)) }
        val bound = requireNotNull(binder(changing).bind(artifact, SERIAL, PACKAGE, false) { 1000 }.state)
        changing.installedBytes = listOf("changed".toByteArray())
        val final = binder(changing).finalCheck(bound, SERIAL, PACKAGE) { 1000 }

        assertEquals("install failed", failed.error)
        assertTrue(splitResult.error?.contains("Split") == true)
        assertNotEquals(artifact.sha256, final.state.afterCapture?.sha256)
    }

    @Test
    fun `propagates cancellation while reading installed bytes`() {
        val artifact = artifact()
        val device =
            object : FakeSmokeDevice() {
                override fun pullApk(
                    serial: String,
                    remotePath: String,
                    destination: Path,
                    timeoutMillis: Long,
                ): DeviceCall<Unit> = DeviceCall(failure = DeviceFailureKind.CANCELLED, detail = "cancelled")
            }.apply { installedBytes = listOf("installed".toByteArray()) }

        val result = binder(device).bind(artifact, SERIAL, PACKAGE, false) { 1000 }

        assertTrue(result.cancelled)
        assertEquals("cancelled", result.error)
    }

    private fun artifact(): StagedArtifact =
        ArtifactBinder(
            FakeSmokeDevice(),
            clock,
        ).snapshot(apk(), directory.resolve("work-${Files.list(directory).use { it.count() }}"))

    private fun apk(): Path = directory.resolve("sample-${System.nanoTime()}.apk").also { Files.writeString(it, "apk bytes") }

    private fun binder(device: FakeSmokeDevice) = ArtifactBinder(device, clock)

    private companion object {
        const val SERIAL = "emulator-5554"
        const val PACKAGE = "io.droidproof.smoke"
    }
}
