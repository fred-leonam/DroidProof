package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.model.EmulatorCapabilityObservationV1
import io.github.fredleonam.droidproof.model.EmulatorEnvironmentState
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryJournalTest {
    @TempDir lateinit var directory: Path

    @Test fun `store round trips atomically and journal transitions are strict`() {
        val store = FileEmulatorRecoveryJournalStore(directory)
        val prepared = journal()
        store.save(prepared)
        assertEquals(prepared, store.load("test-serial"))
        val started = prepared.transition(RecoveryJournalPhase.MUTATION_STARTED, TIME, "Mutation started.")
        store.save(started)
        assertEquals(RecoveryJournalPhase.MUTATION_STARTED, store.load("test-serial")?.phase)
        assertFailsWith<IllegalArgumentException> { prepared.transition(RecoveryJournalPhase.RESTORED_VERIFIED, TIME, "no") }
        assertFalse(Files.list(directory).use { it.anyMatch { path -> path.fileName.toString().endsWith(".tmp") } })
    }

    @Test fun `store rejects symlink oversized corrupt unknown and unsupported documents`() {
        val store = FileEmulatorRecoveryJournalStore(directory)
        val path = store.pathFor("test-serial")
        Files.writeString(path, "{\"unknown\":true}")
        assertFailsWith<RecoveryJournalException> { store.load("test-serial") }
        Files.write(path, ByteArray(33 * 1024))
        assertFailsWith<RecoveryJournalException> { store.load("test-serial") }
        Files.write(path, byteArrayOf(0xC3.toByte(), 0x28))
        assertFailsWith<RecoveryJournalException> { store.load("test-serial") }
        Files.writeString(path, "{\"recoveryJournalSchemaVersion\":2}")
        assertFailsWith<RecoveryJournalException> { store.load("test-serial") }
        val target = directory.resolve("target")
        Files.writeString(target, "x")
        Files.delete(path)
        try {
            Files.createSymbolicLink(path, target.fileName)
            assertFailsWith<RecoveryJournalException> { store.load("test-serial") }
        } catch (_: UnsupportedOperationException) {
            // Filesystems without symlink support are covered by the non-regular checks above.
        }
    }

    @Test fun `recovery restores only matching identity and retains journal after drift`() {
        val store = FileEmulatorRecoveryJournalStore(directory)
        store.save(journal().transition(RecoveryJournalPhase.MUTATION_STARTED, TIME, "Mutation started."))
        val device = FakeSmokeDevice()
        val result =
            EmulatorEnvironmentRecoveryCoordinator(
                device,
                store,
                EmulatorExecutionLeaseProvider {
                    EmulatorExecutionLease {}
                },
                clock,
            ).recover(EmulatorRecoveryRequest("test-serial"))
        assertTrue(result.successful)
        assertEquals(listOf("preflight", "capabilities", "restore", "snapshot"), device.operations.map { it.substringBefore(':') })
        assertTrue(requireNotNull(store.load("test-serial")).isResolved)

        store.save(journal().transition(RecoveryJournalPhase.MUTATION_STARTED, TIME, "Mutation started."))
        device.operations.clear()
        device.capabilityResult = DeviceCall(capability().copy(apiLevel = 34))
        val refused =
            EmulatorEnvironmentRecoveryCoordinator(
                device,
                store,
                EmulatorExecutionLeaseProvider {
                    EmulatorExecutionLease {}
                },
                clock,
            ).recover(EmulatorRecoveryRequest("test-serial"))
        assertFalse(refused.successful)
        assertTrue(device.operations.none { it.startsWith("restore:") })
        assertFalse(requireNotNull(store.load("test-serial")).isResolved)
    }

    private fun journal() =
        EmulatorRecoveryJournalV1(
            transactionId = "recovery-001",
            deviceSerial = "test-serial",
            initialCapability = capability(),
            originalEnvironment = EmulatorEnvironmentState("en-US", 0, 0, 0.0, 0.0, 0.0),
            phase = RecoveryJournalPhase.PREPARED,
            createdAt = TIME,
            updatedAt = TIME,
            detail = "Prepared.",
        )

    private fun capability() =
        EmulatorCapabilityObservationV1(
            1,
            35,
            "generic/sdk",
            "123e4567-e89b-12d3-a456-426614174000",
            listOf("getprop"),
        )

    private companion object {
        const val TIME = "2026-09-16T12:00:00Z"
        val clock = java.time.Clock.fixed(java.time.Instant.parse(TIME), java.time.ZoneOffset.UTC)
    }
}
