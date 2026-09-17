package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.model.ArtifactMutationObservationV1
import io.github.fredleonam.droidproof.model.EmulatorCapabilityObservationV1
import io.github.fredleonam.droidproof.model.EmulatorEnvironmentContractV1
import io.github.fredleonam.droidproof.model.EmulatorEnvironmentEvaluationV1
import io.github.fredleonam.droidproof.model.EnvironmentEvaluationOutcome
import io.github.fredleonam.droidproof.model.EnvironmentMutationObservationV1
import io.github.fredleonam.droidproof.model.IdentityMutationObservationV1
import io.github.fredleonam.droidproof.model.MutationObservationOutcome
import io.github.fredleonam.droidproof.model.TransactionArtifactBaselineV1
import io.github.fredleonam.droidproof.model.TransactionMutationBaselineV1
import io.github.fredleonam.droidproof.model.TransactionMutationCheckpoint
import io.github.fredleonam.droidproof.model.TransactionMutationCheckpointObservationV1
import io.github.fredleonam.droidproof.model.TransactionMutationDocumentV1
import io.github.fredleonam.droidproof.model.UtcTimestamp
import io.github.fredleonam.droidproof.model.aggregateOutcome
import java.time.Clock

internal data class TransactionMutationAttempt(
    val checkpoint: TransactionMutationCheckpointObservationV1,
    val bindingState: ArtifactBindingState,
    val cancelled: Boolean,
)

private data class EnvironmentMutationAttempt(
    val observation: EnvironmentMutationObservationV1,
    val cancelled: Boolean,
)

internal class TransactionMutationRecorder(
    private val device: SmokeDeviceOperations,
    private val initialIdentity: EmulatorCapabilityObservationV1,
    private val initialEnvironment: EmulatorEnvironmentEvaluationV1?,
    private val wallClock: Clock = Clock.systemUTC(),
) {
    private val checkpoints = mutableListOf<TransactionMutationCheckpointObservationV1>()
    private var artifactBaseline: TransactionArtifactBaselineV1? = null

    fun bindArtifact(
        packageName: String,
        state: ArtifactBindingState,
    ) {
        artifactBaseline =
            TransactionArtifactBaselineV1(
                packageName,
                state.stagedArtifact.sha256,
                requireNotNull(state.beforeLaunch.sha256),
            )
    }

    fun observe(
        checkpoint: TransactionMutationCheckpoint,
        afterScenarioStep: Int?,
        serial: String,
        environmentContract: EmulatorEnvironmentContractV1?,
        bindingState: ArtifactBindingState,
        timeoutMillis: () -> Long,
    ): TransactionMutationAttempt {
        val identityCall = device.probeCapabilities(serial, timeoutMillis())
        val identity = identityObservation(identityCall)
        val environmentAttempt = environmentObservation(serial, environmentContract, timeoutMillis)
        val environment = environmentAttempt.observation
        val finalBinding =
            ArtifactBinder(device, wallClock).finalCheck(
                bindingState,
                serial,
                requireNotNull(artifactBaseline).packageName,
                timeoutMillis,
            )
        val artifact = artifactObservation(finalBinding.state)
        val outcome = aggregateOutcome(identity.outcome, environment.outcome, artifact.outcome)
        val observation =
            TransactionMutationCheckpointObservationV1(
                sequence = checkpoints.size + 1,
                checkpoint = checkpoint,
                afterScenarioStep = afterScenarioStep,
                observedAt = UtcTimestamp(wallClock.instant().toString()),
                identity = identity,
                environment = environment,
                artifact = artifact,
                outcome = outcome,
                detail = checkpointDetail(outcome),
            )
        checkpoints += observation
        return TransactionMutationAttempt(
            observation,
            finalBinding.state,
            identityCall.failure == DeviceFailureKind.CANCELLED ||
                environmentAttempt.cancelled ||
                finalBinding.cancelled,
        )
    }

    fun document(): TransactionMutationDocumentV1 {
        val outcome = aggregateOutcome(*checkpoints.map { it.outcome }.toTypedArray())
        return TransactionMutationDocumentV1(
            baseline = TransactionMutationBaselineV1(initialIdentity, initialEnvironment, artifactBaseline),
            checkpoints = checkpoints.toList(),
            outcome = outcome,
            explanation =
                when (outcome) {
                    MutationObservationOutcome.MATCHED ->
                        "All recorded bounded transaction checkpoints matched their available baselines."
                    MutationObservationOutcome.DRIFT_DETECTED ->
                        "Sequential bounded observations detected drift in identity, requested environment, or target APK binding."
                    MutationObservationOutcome.UNAVAILABLE ->
                        "One or more bounded transaction checkpoint observations were unavailable."
                    MutationObservationOutcome.NOT_EVALUATED ->
                        "No bounded transaction checkpoint was reached."
                },
        )
    }

    private fun identityObservation(call: DeviceCall<EmulatorCapabilityObservationV1>): IdentityMutationObservationV1 {
        val observed =
            call.value
                ?: return IdentityMutationObservationV1(
                    MutationObservationOutcome.UNAVAILABLE,
                    detail = "Selected emulator identity was unavailable at this checkpoint.",
                )
        val outcome =
            if (
                observed.apiLevel == initialIdentity.apiLevel &&
                observed.buildFingerprint == initialIdentity.buildFingerprint &&
                observed.bootIdentifier == initialIdentity.bootIdentifier
            ) {
                MutationObservationOutcome.MATCHED
            } else {
                MutationObservationOutcome.DRIFT_DETECTED
            }
        return IdentityMutationObservationV1(
            outcome,
            observed,
            if (outcome == MutationObservationOutcome.MATCHED) {
                "Selected emulator API level, build fingerprint, and boot identifier matched the initial observation."
            } else {
                "Selected emulator API level, build fingerprint, or boot identifier drifted from the initial observation."
            },
        )
    }

    private fun environmentObservation(
        serial: String,
        contract: EmulatorEnvironmentContractV1?,
        timeoutMillis: () -> Long,
    ): EnvironmentMutationAttempt {
        if (contract == null) {
            return EnvironmentMutationAttempt(
                EnvironmentMutationObservationV1(
                    MutationObservationOutcome.NOT_EVALUATED,
                    detail = "No environment contract was present, so environment drift was not evaluated.",
                ),
                false,
            )
        }
        val locale = device.observeLocale(serial, timeoutMillis())
        val orientation = device.observeOrientation(serial, timeoutMillis())
        val animations = device.observeAnimations(serial, timeoutMillis())
        val evaluation =
            EnvironmentEvaluator.evaluate(
                contract,
                locale,
                orientation,
                animations,
            )
        val outcome =
            when (evaluation.outcome) {
                EnvironmentEvaluationOutcome.MATCHED -> MutationObservationOutcome.MATCHED
                EnvironmentEvaluationOutcome.MISMATCHED -> MutationObservationOutcome.DRIFT_DETECTED
                EnvironmentEvaluationOutcome.UNAVAILABLE -> MutationObservationOutcome.UNAVAILABLE
            }
        return EnvironmentMutationAttempt(
            EnvironmentMutationObservationV1(outcome, evaluation, evaluation.explanation),
            listOf(locale, orientation, animations).any { it.failure == DeviceFailureKind.CANCELLED },
        )
    }

    private fun artifactObservation(state: ArtifactBindingState): ArtifactMutationObservationV1 {
        val observed =
            state.afterCapture?.sha256
                ?: return ArtifactMutationObservationV1(
                    MutationObservationOutcome.UNAVAILABLE,
                    detail = "Target package APK binding was unavailable at this checkpoint.",
                )
        val outcome =
            if (observed == requireNotNull(artifactBaseline).inputApkSha256) {
                MutationObservationOutcome.MATCHED
            } else {
                MutationObservationOutcome.DRIFT_DETECTED
            }
        return ArtifactMutationObservationV1(
            outcome,
            observed,
            if (outcome == MutationObservationOutcome.MATCHED) {
                "Target package APK bytes matched the bound input APK."
            } else {
                "Target package APK bytes drifted from the bound input APK."
            },
        )
    }

    private fun checkpointDetail(outcome: MutationObservationOutcome): String =
        when (outcome) {
            MutationObservationOutcome.MATCHED -> "All evaluated observations matched at this bounded checkpoint."
            MutationObservationOutcome.DRIFT_DETECTED -> "Relevant observable transaction drift was detected at this checkpoint."
            MutationObservationOutcome.UNAVAILABLE -> "A required transaction observation was unavailable at this checkpoint."
            MutationObservationOutcome.NOT_EVALUATED -> "No transaction observation was evaluated at this checkpoint."
        }
}
