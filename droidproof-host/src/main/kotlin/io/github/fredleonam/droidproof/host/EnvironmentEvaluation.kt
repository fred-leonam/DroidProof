package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.model.EmulatorEnvironmentContractV1
import io.github.fredleonam.droidproof.model.EmulatorEnvironmentEvaluationV1
import io.github.fredleonam.droidproof.model.EmulatorEnvironmentObservations
import io.github.fredleonam.droidproof.model.EnvironmentEvaluationOutcome
import io.github.fredleonam.droidproof.model.EnvironmentFieldEvaluation
import io.github.fredleonam.droidproof.model.EnvironmentObservation
import io.github.fredleonam.droidproof.model.ObservedAnimationScales

internal object EnvironmentEvaluator {
    fun evaluate(
        requested: EmulatorEnvironmentContractV1,
        localeCall: DeviceCall<DeviceLocaleObservation>,
        orientationCall: DeviceCall<DeviceOrientationObservation>,
        animationsCall: DeviceCall<DeviceAnimationObservations>,
    ): EmulatorEnvironmentEvaluationV1 {
        val locale =
            localeCall.value?.let { EnvironmentObservation(it.normalized, it.rawSafe) }
                ?: unavailable(localeCall, "Locale could not be observed.")
        val orientation =
            orientationCall.value?.let { EnvironmentObservation(it.orientation.name, it.rawSafe) }
                ?: unavailable(orientationCall, "Orientation could not be observed.")
        val animationValues = animationsCall.value
        val animations =
            if (animationValues == null) {
                val observation = unavailable(animationsCall, "Animation scales could not be observed.")
                ObservedAnimationScales(observation, observation, observation)
            } else {
                ObservedAnimationScales(
                    numericObservation(animationValues.windowScale),
                    numericObservation(animationValues.transitionScale),
                    numericObservation(animationValues.animatorScale),
                )
            }
        val observations = EmulatorEnvironmentObservations(locale, orientation, animations)
        val fields =
            listOf(
                compare("locale", requested.locale, locale),
                compare("orientation", requested.orientation.name, orientation),
                compareNumber("animations.windowScale", requested.animations.windowScale, animations.windowScale),
                compareNumber("animations.transitionScale", requested.animations.transitionScale, animations.transitionScale),
                compareNumber("animations.animatorScale", requested.animations.animatorScale, animations.animatorScale),
            )
        val outcome =
            when {
                fields.any { it.outcome == EnvironmentEvaluationOutcome.UNAVAILABLE } -> EnvironmentEvaluationOutcome.UNAVAILABLE
                fields.any { it.outcome == EnvironmentEvaluationOutcome.MISMATCHED } -> EnvironmentEvaluationOutcome.MISMATCHED
                else -> EnvironmentEvaluationOutcome.MATCHED
            }
        val explanation =
            when (outcome) {
                EnvironmentEvaluationOutcome.MATCHED -> "All requested emulator environment fields matched validated observations."
                EnvironmentEvaluationOutcome.MISMATCHED -> "One or more requested emulator environment fields did not match."
                EnvironmentEvaluationOutcome.UNAVAILABLE -> "One or more required emulator environment observations were unavailable."
            }
        return EmulatorEnvironmentEvaluationV1(
            requested = requested,
            observed = observations,
            fields = fields,
            outcome = outcome,
            explanation = explanation,
        )
    }

    private fun unavailable(
        call: DeviceCall<*>,
        fallback: String,
    ): EnvironmentObservation = EnvironmentObservation(unavailableReason = sanitizedReason(call.failure, fallback))

    private fun sanitizedReason(
        failure: DeviceFailureKind?,
        fallback: String,
    ): String =
        when (failure) {
            DeviceFailureKind.TIMEOUT -> "The bounded ADB observation timed out."
            DeviceFailureKind.CANCELLED -> "The ADB observation was cancelled."
            DeviceFailureKind.DISCONNECTED -> "The selected emulator became unavailable."
            DeviceFailureKind.UNSUPPORTED -> "The observation is unsupported by this milestone."
            DeviceFailureKind.INVALID_OUTPUT -> "ADB returned an unsupported observation value."
            DeviceFailureKind.COMMAND -> "The bounded ADB observation command failed."
            null -> fallback
        }

    private fun numericObservation(value: Double): EnvironmentObservation {
        val normalized = normalizedNumber(value)
        return EnvironmentObservation(normalized, normalized)
    }

    private fun compare(
        field: String,
        requested: String,
        observed: EnvironmentObservation,
    ): EnvironmentFieldEvaluation {
        val actual = observed.normalizedValue
        val outcome =
            when {
                actual == null -> EnvironmentEvaluationOutcome.UNAVAILABLE
                actual == requested -> EnvironmentEvaluationOutcome.MATCHED
                else -> EnvironmentEvaluationOutcome.MISMATCHED
            }
        return EnvironmentFieldEvaluation(field, requested, actual, outcome, explanation(field, outcome))
    }

    private fun compareNumber(
        field: String,
        requested: Double,
        observed: EnvironmentObservation,
    ): EnvironmentFieldEvaluation {
        val expected = normalizedNumber(requested)
        val actual = observed.normalizedValue
        val outcome =
            when {
                actual == null -> EnvironmentEvaluationOutcome.UNAVAILABLE
                actual.toDouble() == requested -> EnvironmentEvaluationOutcome.MATCHED
                else -> EnvironmentEvaluationOutcome.MISMATCHED
            }
        return EnvironmentFieldEvaluation(field, expected, actual, outcome, explanation(field, outcome))
    }

    private fun explanation(
        field: String,
        outcome: EnvironmentEvaluationOutcome,
    ): String =
        when (outcome) {
            EnvironmentEvaluationOutcome.MATCHED -> "$field matched."
            EnvironmentEvaluationOutcome.MISMATCHED -> "$field did not match."
            EnvironmentEvaluationOutcome.UNAVAILABLE -> "$field was unavailable."
        }

    private fun normalizedNumber(value: Double): String = value.toString()
}
