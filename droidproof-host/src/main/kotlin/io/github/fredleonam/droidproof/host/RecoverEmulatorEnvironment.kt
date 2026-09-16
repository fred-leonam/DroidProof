package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.AdbPathResolver
import java.nio.file.Path
import java.time.Clock

data class EmulatorRecoveryRequest(
    val deviceSerial: String,
    val commandTimeoutMillis: Long = 15_000,
) {
    init {
        require(DEVICE_SERIAL.matches(deviceSerial)) { "An explicit valid test-emulator serial is required." }
        require(commandTimeoutMillis in 1..3_600_000) { "Command timeout is outside supported bounds." }
    }
}

data class EmulatorRecoveryResult(val successful: Boolean, val detail: String)

/** Explicit, deliberately narrow rollback of a durable APPLY_AND_RESTORE transaction. */
class EmulatorEnvironmentRecoveryCoordinator(
    private val device: SmokeDeviceOperations,
    private val journalStore: EmulatorRecoveryJournalStore,
    private val leaseProvider: EmulatorExecutionLeaseProvider = FileEmulatorExecutionLeaseProvider(),
    private val wallClock: Clock = Clock.systemUTC(),
) {
    fun recover(request: EmulatorRecoveryRequest): EmulatorRecoveryResult {
        val lease =
            try {
                leaseProvider.acquire(request.deviceSerial)
            } catch (
                _: EmulatorExecutionLeaseUnavailableException,
            ) {
                return EmulatorRecoveryResult(false, "Recovery could not acquire the emulator lease.")
            }
        try {
            val journal =
                try {
                    journalStore.load(request.deviceSerial)
                } catch (
                    _: RecoveryJournalException,
                ) {
                    return EmulatorRecoveryResult(false, "Recovery journal is unsafe or unreadable; preserve it and inspect manually.")
                }
                    ?: return EmulatorRecoveryResult(false, "No recovery journal exists for the selected serial.")
            if (journal.isResolved) return EmulatorRecoveryResult(true, "Recovery journal is already resolved.")
            val preflight = device.preflight(request.deviceSerial, request.commandTimeoutMillis)
            if (!preflight.isSuccessful) return EmulatorRecoveryResult(false, "Recovery preflight failed; journal was preserved.")
            val observed = device.probeCapabilities(request.deviceSerial, request.commandTimeoutMillis)
            val capability =
                observed.value
                    ?: return EmulatorRecoveryResult(false, manualGuidance(journal, "Capability observation was unavailable."))
            if (!sameIdentity(capability, journal.initialCapability)) {
                return EmulatorRecoveryResult(false, manualGuidance(journal, "Emulator API, fingerprint, or boot identifier drifted."))
            }
            val restoring =
                journal.transition(
                    RecoveryJournalPhase.RESTORATION_STARTED,
                    wallClock.instant().toString(),
                    "Explicit recovery restoration started.",
                )
            try {
                journalStore.save(restoring)
            } catch (
                _: Exception,
            ) {
                return EmulatorRecoveryResult(false, "Recovery journal could not record restoration; no device mutation was attempted.")
            }
            val restored = device.restoreEnvironment(request.deviceSerial, journal.originalEnvironment, request.commandTimeoutMillis)
            if (!restored.isSuccessful) {
                retainRequired(restoring, "Explicit recovery restoration failed.")
                return EmulatorRecoveryResult(false, "Recovery restoration failed; journal was retained.")
            }
            val after = device.snapshotEnvironment(request.deviceSerial, request.commandTimeoutMillis)
            if (after.value != journal.originalEnvironment) {
                retainRequired(restoring, "Explicit recovery verification failed.")
                return EmulatorRecoveryResult(false, "Recovery verification failed; journal was retained.")
            }
            return try {
                journalStore.save(
                    restoring.transition(
                        RecoveryJournalPhase.RESTORED_VERIFIED,
                        wallClock.instant().toString(),
                        "Explicit recovery restored and verified original environment.",
                    ),
                )
                EmulatorRecoveryResult(true, "Recovery restored and verified the saved environment.")
            } catch (_: Exception) {
                retainRequired(restoring, "Recovery journal finalization failed.")
                EmulatorRecoveryResult(false, "Recovery was verified but journal finalization failed; journal was retained.")
            }
        } finally {
            lease.close()
        }
    }

    private fun retainRequired(
        journal: EmulatorRecoveryJournalV1,
        detail: String,
    ) {
        runCatching {
            journalStore.save(
                journal.transition(RecoveryJournalPhase.RECOVERY_REQUIRED, wallClock.instant().toString(), detail),
            )
        }
    }

    private fun sameIdentity(
        a: io.github.fredleonam.droidproof.model.EmulatorCapabilityObservationV1,
        b: io.github.fredleonam.droidproof.model.EmulatorCapabilityObservationV1,
    ) = a.apiLevel == b.apiLevel && a.buildFingerprint == b.buildFingerprint && a.bootIdentifier == b.bootIdentifier

    private fun manualGuidance(
        journal: EmulatorRecoveryJournalV1,
        reason: String,
    ): String =
        "$reason Automatic restoration was refused; journal retained. " +
            "Saved original values: locale=${journal.originalEnvironment.locale}, " +
            "accelerometerRotation=${journal.originalEnvironment.accelerometerRotation}, " +
            "userRotation=${journal.originalEnvironment.userRotation}, " +
            "windowScale=${journal.originalEnvironment.windowScale}, " +
            "transitionScale=${journal.originalEnvironment.transitionScale}, " +
            "animatorScale=${journal.originalEnvironment.animatorScale}."
}

fun main(args: Array<String>) {
    require(args.size == 4) { "Expected recovery task configuration arguments." }
    require(args[0].isNotBlank()) { "Set -Pdroidproof.deviceSerial to an authorized test emulator serial." }
    val adbPath = AdbPathResolver.resolve(args[1].takeIf(String::isNotBlank))
    val result =
        EmulatorEnvironmentRecoveryCoordinator(
            SmokeAdbClient(adbPath),
            FileEmulatorRecoveryJournalStore(Path.of(args[2])),
        ).recover(EmulatorRecoveryRequest(args[0]))
    println("DroidProof environment recovery: ${result.detail}")
    check(result.successful) { result.detail }
}
