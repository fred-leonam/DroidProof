package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CaptureLimits
import io.github.fredleonam.droidproof.device.CaptureRequest
import io.github.fredleonam.droidproof.device.CaptureResult
import io.github.fredleonam.droidproof.device.CollectionIssueCode
import io.github.fredleonam.droidproof.device.CollectionOutcome
import io.github.fredleonam.droidproof.evidence.EvidenceBundleRequestV3
import io.github.fredleonam.droidproof.evidence.EvidenceBundleVerifier
import io.github.fredleonam.droidproof.evidence.EvidenceBundleWriter
import io.github.fredleonam.droidproof.evidence.EvidenceFileInput
import io.github.fredleonam.droidproof.evidence.V3_SCHEMA_VERSION
import io.github.fredleonam.droidproof.mockserver.DeterministicMockServer
import io.github.fredleonam.droidproof.mockserver.MockServerStarter
import io.github.fredleonam.droidproof.model.AndroidArtifactIdentity
import io.github.fredleonam.droidproof.model.AndroidArtifactType
import io.github.fredleonam.droidproof.model.ArtifactBindingStatus
import io.github.fredleonam.droidproof.model.ArtifactBindingSummary
import io.github.fredleonam.droidproof.model.BundleId
import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.DroidProofVersion
import io.github.fredleonam.droidproof.model.EmulatorEnvironmentEvaluationV1
import io.github.fredleonam.droidproof.model.EmulatorEnvironmentState
import io.github.fredleonam.droidproof.model.EnvironmentEvaluationOutcome
import io.github.fredleonam.droidproof.model.EnvironmentExecutionMode
import io.github.fredleonam.droidproof.model.EnvironmentObservation
import io.github.fredleonam.droidproof.model.EnvironmentRestorationOutcome
import io.github.fredleonam.droidproof.model.EnvironmentTransactionDocument
import io.github.fredleonam.droidproof.model.EventId
import io.github.fredleonam.droidproof.model.EventSource
import io.github.fredleonam.droidproof.model.EvidenceBundleManifestV3
import io.github.fredleonam.droidproof.model.EvidenceCompleteness
import io.github.fredleonam.droidproof.model.EvidenceFileRole
import io.github.fredleonam.droidproof.model.EvidenceReference
import io.github.fredleonam.droidproof.model.ExecutionStatus
import io.github.fredleonam.droidproof.model.ExecutionSummary
import io.github.fredleonam.droidproof.model.ObservedExecutionEnvironment
import io.github.fredleonam.droidproof.model.ObservedValue
import io.github.fredleonam.droidproof.model.RequestedExecutionConfiguration
import io.github.fredleonam.droidproof.model.ScenarioIdentity
import io.github.fredleonam.droidproof.model.ScenarioVerdict
import io.github.fredleonam.droidproof.model.TimelineEvent
import io.github.fredleonam.droidproof.model.UtcTimestamp
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SmokeRunRequest(
    val apkPath: Path,
    val scenarioPath: Path,
    val deviceSerial: String,
    val outputRoot: Path,
    val environmentPath: Path? = null,
    val environmentMode: EnvironmentExecutionMode = EnvironmentExecutionMode.VERIFY_ONLY,
    val replaceExisting: Boolean = false,
    val droidProofVersion: DroidProofVersion,
    val commandTimeoutMillis: Long = 15_000,
) {
    init {
        require(deviceSerial.isNotBlank()) { "An explicit test-emulator serial is required." }
        require(commandTimeoutMillis in 1..3_600_000) { "Command timeout is outside supported bounds." }
        require(environmentMode != EnvironmentExecutionMode.APPLY_AND_RESTORE || environmentPath != null) {
            "APPLY_AND_RESTORE requires droidproof.environmentPath."
        }
    }
}

fun interface DeviceEvidenceCapture {
    fun capture(request: CaptureRequest): CaptureResult
}

fun interface BundlePublisher {
    fun publish(
        request: EvidenceBundleRequestV3,
        destination: Path,
    ): Path
}

class SmokeCoordinator(
    private val device: SmokeDeviceOperations,
    private val capture: DeviceEvidenceCapture,
    private val publisher: BundlePublisher = BundlePublisher { request, destination -> EvidenceBundleWriter().write(request, destination) },
    private val verifier: EvidenceBundleVerifier = EvidenceBundleVerifier(),
    private val wallClock: Clock = Clock.systemUTC(),
    private val monotonicClock: MonotonicClock = SystemMonotonicClock,
    private val cancellation: CancellationSignal = SystemCancellationSignal,
    private val assertionRunner: UiAssertionRunner =
        UiAssertionRunner(device, monotonicClock = monotonicClock, cancellation = cancellation),
    private val mockServerStarter: MockServerStarter = DeterministicMockServer(wallClock),
    private val idSource: () -> String = { UUID.randomUUID().toString() },
    private val leaseProvider: EmulatorExecutionLeaseProvider = FileEmulatorExecutionLeaseProvider(),
) {
    fun run(request: SmokeRunRequest): SmokeRunResult {
        val startedAt = wallClock.instant().toString()
        val executionId = idSource()
        require(SAFE_RUN_ID.matches(executionId)) { "Execution ID must be a lower-case slug." }
        Files.createDirectories(request.outputRoot)
        val runDirectory = Files.createTempDirectory(request.outputRoot, "$executionId-")
        val workDirectory = Files.createDirectory(runDirectory.resolve("work"))
        val state = MutableExecutionState(startedAt) { wallClock.instant().toString() }
        var lease: EmulatorExecutionLease? = null

        try {
            var accepted: AcceptedInputs? = null
            stage<Unit>(state, ExecutionStage.PREFLIGHT) {
                ensureActive()
                val scenario = SmokeScenarioLoader.load(request.scenarioPath)
                val artifact = ArtifactBinder(device, wallClock).snapshot(request.apkPath, workDirectory)
                val environment = request.environmentPath?.let(EnvironmentContractLoader::load)
                accepted = AcceptedInputs(scenario, artifact, environment)
                lease = leaseProvider.acquire(request.deviceSerial)
                val overallBudget =
                    scenario.scenario.orderedSteps.filterIsInstance<AssertUiNode>().sumOf { it.deadlineMillis } +
                        scenario.scenario.orderedSteps.sumOf { it.stepType.deviceOperationCount } * request.commandTimeoutMillis +
                        request.commandTimeoutMillis * MAX_DEVICE_OPERATIONS +
                        FINALIZATION_BUDGET_MILLIS
                state.overallDeadlineNanos =
                    monotonicClock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(overallBudget.coerceAtMost(MAX_OVERALL_BUDGET_MILLIS))
                val preflight = device.preflight(request.deviceSerial, operationTimeout(request, state))
                if (!preflight.isSuccessful) abort(preflight.detail ?: "Device preflight failed.", preflight.failure)
            }
            val inputs = accepted

            var environmentEvaluation: EmulatorEnvironmentEvaluationV1? = null
            var environmentTransaction: EnvironmentTransactionDocument? = null
            var originalEnvironment: EmulatorEnvironmentState? = null
            if (inputs?.environment != null && state.canUseDevice()) {
                stage<Unit>(state, ExecutionStage.ENVIRONMENT) {
                    ensureActive()
                    if (request.environmentMode == EnvironmentExecutionMode.APPLY_AND_RESTORE) {
                        val snapshot = device.snapshotEnvironment(request.deviceSerial, operationTimeout(request, state))
                        if (!snapshot.isSuccessful) {
                            abort(
                                snapshot.detail ?: "Original emulator environment could not be captured.",
                                snapshot.failure,
                            )
                        }
                        originalEnvironment = requireNotNull(snapshot.value)
                        environmentTransaction =
                            EnvironmentTransactionDocument(
                                mode = request.environmentMode,
                                original = originalEnvironment,
                                detail = "Original environment was observed before mutation.",
                                restorationOutcome = EnvironmentRestorationOutcome.NOT_ATTEMPTED,
                            )
                        val apply =
                            device.applyEnvironment(
                                request.deviceSerial,
                                inputs.environment.contract,
                                operationTimeout(request, state),
                            )
                        environmentTransaction =
                            requireNotNull(environmentTransaction).copy(
                                mutationAttempted = true,
                                detail = "Requested environment mutation was attempted.",
                            )
                        if (!apply.isSuccessful) {
                            abort(
                                apply.detail ?: "Requested emulator environment could not be applied.",
                                apply.failure,
                            )
                        }
                    }
                    val locale = device.observeLocale(request.deviceSerial, operationTimeout(request, state))
                    if (locale.failure == DeviceFailureKind.CANCELLED) {
                        abort("Environment observation was cancelled.", locale.failure)
                    }
                    ensureActive()
                    val orientation =
                        device.observeOrientation(request.deviceSerial, operationTimeout(request, state))
                    if (orientation.failure == DeviceFailureKind.CANCELLED) {
                        abort(
                            "Environment observation was cancelled.",
                            orientation.failure,
                        )
                    }
                    ensureActive()
                    val animations = device.observeAnimations(request.deviceSerial, operationTimeout(request, state))
                    if (animations.failure == DeviceFailureKind.CANCELLED) {
                        abort(
                            "Environment observation was cancelled.",
                            animations.failure,
                        )
                    }
                    environmentEvaluation =
                        EnvironmentEvaluator.evaluate(inputs.environment.contract, locale, orientation, animations)
                    if (environmentEvaluation?.outcome != EnvironmentEvaluationOutcome.MATCHED) {
                        throw RunAbort(requireNotNull(environmentEvaluation).explanation)
                    }
                    if (environmentTransaction != null) {
                        environmentTransaction =
                            requireNotNull(environmentTransaction).copy(
                                requestedVerification = EnvironmentEvaluationOutcome.MATCHED,
                                detail = "Requested environment was verified before scenario execution.",
                            )
                    }
                }
            } else {
                val detail =
                    if (inputs?.environment == null && inputs != null) {
                        "No environment contract was requested."
                    } else {
                        "A prior fatal stage prevented environment observation."
                    }
                state.skip(ExecutionStage.ENVIRONMENT, detail)
            }

            var bindingAttempt: BindingAttempt? = null
            var bindingState: ArtifactBindingState? = null
            if (inputs != null && state.canUseDevice()) {
                stage<Unit>(state, ExecutionStage.ARTIFACT_BINDING) {
                    val binder = ArtifactBinder(device, wallClock)
                    val attempt =
                        binder.bind(
                            inputs.artifact,
                            request.deviceSerial,
                            inputs.scenario.scenario.expectedPackage,
                            request.replaceExisting,
                        ) { operationTimeout(request, state) }
                    bindingAttempt = attempt
                    if (attempt.error != null) {
                        throw RunAbort(attempt.error, attempt.cancelled)
                    }
                    bindingState = requireNotNull(attempt.state)
                }
            } else {
                state.skip(ExecutionStage.ARTIFACT_BINDING)
            }

            val backendPlan = inputs?.scenario?.scenario?.backendPlan
            var networkSession: ActiveNetworkSession? = null
            var networkEvaluation: NetworkEvaluationDocument? =
                backendPlan?.let {
                    NetworkEvaluationDocument(
                        NetworkEvaluationOutcome.NOT_EVALUATED,
                        it.responsePlan.size,
                        0,
                        detail = "Network setup was not completed.",
                    )
                }
            if (backendPlan != null && bindingState != null && state.canUseDevice()) {
                stage<Unit>(state, ExecutionStage.NETWORK_SETUP) {
                    ensureActive()
                    val setup =
                        NetworkSessionManager(device, mockServerStarter).start(
                            backendPlan,
                            request.deviceSerial,
                            operationTimeout(request, state),
                        )
                    setup.cleanupDetail?.let { state.observations += HostObservation(wallClock.instant().toString(), "cleanup", it) }
                    if (setup.failureDetail != null) throw RunAbort(setup.failureDetail, setup.cancelled)
                    networkSession = requireNotNull(setup.session)
                }
            } else if (backendPlan != null) {
                state.skip(ExecutionStage.NETWORK_SETUP)
            }

            if (bindingState != null && state.canUseDevice()) {
                stage<Unit>(state, ExecutionStage.LAUNCH) {
                    ensureActive()
                    val result =
                        device.launch(
                            request.deviceSerial,
                            requireNotNull(inputs).scenario.scenario.launchComponent,
                            operationTimeout(request, state),
                        )
                    if (!result.isSuccessful) abort(result.detail ?: "Activity launch failed.", result.failure)
                }
            } else {
                state.skip(ExecutionStage.LAUNCH)
            }

            if (state.canUseDevice() && inputs != null) {
                stage<Unit>(state, ExecutionStage.ASSERTION) {
                    executeSteps(request, state, inputs.scenario.scenario, workDirectory)
                }
            } else {
                state.skip(ExecutionStage.ASSERTION)
                inputs?.let { skipRemainingSteps(state, it.scenario.scenario) }
            }
            val assertion = state.assertionAttempt

            var captureResult: CaptureResult? = null
            if (state.canUseDevice() && inputs != null) {
                captureResult =
                    captureStage(request, state, workDirectory).also { result ->
                        val fatal = result?.document?.issues?.firstOrNull { it.code in FATAL_CAPTURE_ISSUES }
                        if (fatal != null) state.failAfterStage(fatal.message, fatal.code == CollectionIssueCode.INTERRUPTED)
                    }
                if (state.canUseDevice() && bindingState != null) {
                    try {
                        val currentBinding = requireNotNull(bindingState)
                        val finalCheck =
                            ArtifactBinder(device, wallClock).finalCheck(
                                currentBinding,
                                request.deviceSerial,
                                inputs.scenario.scenario.expectedPackage,
                            ) { operationTimeout(request, state) }
                        bindingState = finalCheck.state
                        val finalDigest = bindingState?.afterCapture?.sha256
                        if (finalDigest != inputs.artifact.sha256) {
                            state.failAfterStage(
                                bindingState?.afterCapture?.unavailableReason
                                    ?: "Installed APK bytes changed after assertion and capture.",
                                finalCheck.cancelled,
                            )
                        }
                    } catch (error: RunAbort) {
                        state.failAfterStage(error.message ?: "Final artifact identity check failed.", error.cancelled)
                    } catch (_: Exception) {
                        state.failAfterStage("Final artifact identity check failed.")
                    }
                }
            } else {
                state.skip(ExecutionStage.CAPTURE)
            }

            var networkFiles = emptyList<EvidenceFileInput>()
            var networkEvents = emptyList<TimelineEvent>()
            if (backendPlan != null && networkSession != null) {
                val executionFailedBeforeEvaluation = state.primaryError != null || state.cancelled
                val finish =
                    stage<NetworkFinishResult>(state, ExecutionStage.NETWORK_EVALUATION) {
                        requireNotNull(networkSession).finish(workDirectory, request.commandTimeoutMillis)
                    }
                if (finish != null) {
                    networkEvaluation =
                        if (executionFailedBeforeEvaluation && finish.evaluation.outcome != NetworkEvaluationOutcome.NOT_EVALUATED) {
                            finish.evaluation.copy(
                                outcome = NetworkEvaluationOutcome.NOT_EVALUATED,
                                detail = "Network expectations were not evaluated because execution did not reach a behavioral outcome.",
                            )
                        } else {
                            finish.evaluation
                        }
                    networkFiles = finish.evidenceFiles
                    networkEvents = finish.timelineEvents
                    if (finish.evaluation.outcome == NetworkEvaluationOutcome.NOT_EVALUATED && !executionFailedBeforeEvaluation) {
                        state.recordFailure(finish.evaluation.detail, false)
                    }
                    finish.cleanupDetail?.let { state.recordFailure(it, false) }
                }
            } else if (backendPlan != null) {
                state.skip(ExecutionStage.NETWORK_EVALUATION)
            }

            if (originalEnvironment != null) {
                stage<Unit>(state, ExecutionStage.ENVIRONMENT_RESTORE) {
                    // Do not honor cooperative cancellation here: rollback has a separate bounded timeout.
                    val restored =
                        device.restoreEnvironment(request.deviceSerial, originalEnvironment!!, request.commandTimeoutMillis)
                    if (!restored.isSuccessful) {
                        environmentTransaction =
                            requireNotNull(environmentTransaction).copy(
                                restorationAttempted = true,
                                restorationOutcome = EnvironmentRestorationOutcome.RESTORE_UNAVAILABLE,
                                detail = restored.detail ?: "Environment restoration was unavailable.",
                            )
                        throw RunAbort(requireNotNull(environmentTransaction).detail)
                    }
                    val observed = device.snapshotEnvironment(request.deviceSerial, request.commandTimeoutMillis)
                    val matches = observed.value == originalEnvironment
                    environmentTransaction =
                        requireNotNull(environmentTransaction).copy(
                            restorationAttempted = true,
                            restored = observed.value,
                            restorationOutcome =
                                if (matches) {
                                    EnvironmentRestorationOutcome.RESTORED
                                } else if (observed.isSuccessful) {
                                    EnvironmentRestorationOutcome.RESTORE_MISMATCH
                                } else {
                                    EnvironmentRestorationOutcome.RESTORE_UNAVAILABLE
                                },
                            detail =
                                if (matches) {
                                    "Original low-level environment state was restored and verified."
                                } else {
                                    "Original low-level environment state could not be verified after restoration."
                                },
                        )
                    if (!matches) throw RunAbort(requireNotNull(environmentTransaction).detail)
                }
            } else if (inputs?.environment != null) {
                environmentTransaction =
                    EnvironmentTransactionDocument(
                        mode = request.environmentMode,
                        restorationOutcome = EnvironmentRestorationOutcome.NOT_REQUIRED,
                        detail = "Verify-only mode did not mutate the emulator.",
                    )
            }

            val bindingDocument =
                bindingState?.let {
                    ArtifactBindingDocument(
                        packageName = requireNotNull(inputs).scenario.scenario.expectedPackage,
                        inputApkSha256 = it.stagedArtifact.sha256,
                        action = it.action,
                        beforeLaunch = it.beforeLaunch,
                        afterCapture = it.afterCapture,
                    )
                } ?: bindingAttempt?.document
                    ?: inputs?.let {
                        ArtifactBindingDocument(
                            packageName = it.scenario.scenario.expectedPackage,
                            inputApkSha256 = it.artifact.sha256,
                            action = InstallationAction.NOT_ATTEMPTED,
                        )
                    }

            if (inputs == null || bindingDocument == null) {
                val diagnostic = writeDiagnostic(runDirectory, executionId, state, "A truthful schema-v3 bundle could not be constructed.")
                deleteWorkDirectory(workDirectory)
                return SmokeRunResult(null, diagnostic, null, false)
            }
            val result =
                finalizeBundle(
                    request,
                    runDirectory,
                    workDirectory,
                    executionId,
                    inputs,
                    bindingDocument,
                    bindingState,
                    assertion,
                    captureResult,
                    networkEvaluation,
                    networkFiles,
                    networkEvents,
                    environmentEvaluation,
                    environmentTransaction,
                    state,
                )
            deleteWorkDirectory(workDirectory)
            return result
        } finally {
            lease?.close()
        }
    }

    private fun executeSteps(
        request: SmokeRunRequest,
        state: MutableExecutionState,
        scenario: ScenarioDefinition,
        workDirectory: Path,
    ) {
        try {
            for ((index, step) in scenario.orderedSteps.withIndex()) {
                val started = wallClock.instant().toString()
                val type = step.stepType
                val suffix = type.hierarchySuffix
                val path =
                    if (scenario.schemaVersion == 1) {
                        HIERARCHY_PATH
                    } else {
                        BundleRelativePath("ui/steps/${(index + 1).toString().padStart(3, '0')}-$suffix.xml")
                    }
                var retained: BundleRelativePath? = null
                var document: AssertionDocument? = null
                try {
                    operationTimeout(request, state)
                    val stepDirectory = Files.createDirectories(workDirectory.resolve("steps/$index"))
                    when (step) {
                        is TapUiNode, is TypeTextUiNode -> {
                            val local = stepDirectory.resolve("$suffix.xml")
                            val remote = "/sdcard/Download/droidproof-${UUID.randomUUID()}.xml"
                            val dump =
                                device.dumpHierarchy(
                                    request.deviceSerial,
                                    remote,
                                    local,
                                    operationTimeout(request, state),
                                    2L * 1024L * 1024L,
                                )
                            if (!dump.isSuccessful) abort("UI target hierarchy collection failed.", dump.failure)
                            // Retain only bounded, safely parsed XML, including an unresolved target observation.
                            val resolution = UiHierarchyParser().inspectTap(local, scenario.expectedPackage, step.resourceId)
                            state.stepFiles += EvidenceFileInput(local, path, "application/xml", EvidenceFileRole.SEMANTICS)
                            retained = path
                            val coordinates = resolution.coordinatesOrThrow()
                            val tap = device.tap(request.deviceSerial, coordinates, operationTimeout(request, state))
                            if (!tap.isSuccessful) abort("UI tap command failed.", tap.failure)
                            if (step is TypeTextUiNode) {
                                val input = device.inputText(request.deviceSerial, step.text, operationTimeout(request, state))
                                if (!input.isSuccessful) abort("UI text input command failed.", input.failure)
                            }
                        }
                        is AssertUiNode -> {
                            val attempt =
                                assertionRunner.await(
                                    scenario.expectedPackage,
                                    step,
                                    request.deviceSerial,
                                    stepDirectory,
                                    path,
                                ) { remaining -> minOf(remaining, operationTimeout(request, state)) }
                            state.assertionAttempt = attempt
                            document = attempt.document
                            attempt.hierarchySource?.let {
                                state.stepFiles += EvidenceFileInput(it, path, "application/xml", EvidenceFileRole.SEMANTICS)
                                retained = path
                            }
                            if (attempt.error != null) throw RunAbort(attempt.error, attempt.cancelled)
                        }
                    }
                    operationTimeout(request, state)
                    val status =
                        if (document?.outcome == AssertionOutcome.NOT_MATCHED) {
                            StepStatus.ASSERTION_FAILED
                        } else {
                            StepStatus.SUCCEEDED
                        }
                    state.steps +=
                        StepOutcome(
                            index + 1, type, status, started, wallClock.instant().toString(), document?.detail, retained, document,
                        )
                    if (status == StepStatus.ASSERTION_FAILED) break
                } catch (error: Exception) {
                    val cancelled = error is RunAbort && error.cancelled
                    val detail =
                        when (error) {
                            is RunAbort -> error.message ?: "Step execution failed."
                            is HierarchyValidationException -> error.message ?: "UI hierarchy was invalid."
                            else -> "Step execution failed before a trustworthy result was available."
                        }
                    state.steps +=
                        StepOutcome(
                            index + 1, type, if (cancelled) StepStatus.CANCELLED else StepStatus.ERROR,
                            started, wallClock.instant().toString(), detail, retained, document,
                        )
                    throw RunAbort(detail, cancelled)
                }
            }
        } finally {
            skipRemainingSteps(state, scenario)
        }
    }

    private fun skipRemainingSteps(
        state: MutableExecutionState,
        scenario: ScenarioDefinition,
    ) {
        for (index in state.steps.size until scenario.orderedSteps.size) {
            val step = scenario.orderedSteps[index]
            val now = wallClock.instant().toString()
            state.steps +=
                StepOutcome(
                    index + 1, step.stepType,
                    StepStatus.SKIPPED, now, now, "A prior outcome prevented this step from starting.",
                )
        }
    }

    private fun captureStage(
        request: SmokeRunRequest,
        state: MutableExecutionState,
        workDirectory: Path,
    ): CaptureResult? {
        val stageStarted = wallClock.instant().toString()
        return try {
            ensureActive()
            val timeout = operationTimeout(request, state)
            val result =
                capture.capture(
                    CaptureRequest(
                        outputRoot = workDirectory.resolve("capture"),
                        serial = request.deviceSerial,
                        includeLogcat = false,
                        limits = CaptureLimits(commandTimeoutMillis = timeout),
                    ),
                )
            val status = if (result.document.screenshot == CollectionOutcome.SUCCESS) StageStatus.SUCCEEDED else StageStatus.FAILED
            state.stages +=
                StageOutcome(
                    ExecutionStage.CAPTURE,
                    status,
                    stageStarted,
                    wallClock.instant().toString(),
                    "Screenshot collection outcome: ${result.document.screenshot}.",
                )
            result
        } catch (error: RunAbort) {
            state.stages +=
                StageOutcome(
                    ExecutionStage.CAPTURE,
                    if (error.cancelled) StageStatus.CANCELLED else StageStatus.FAILED,
                    stageStarted,
                    wallClock.instant().toString(),
                    error.message,
                )
            state.failAfterStage(error.message ?: "Capture was cancelled.", error.cancelled)
            null
        } catch (error: Exception) {
            state.stages +=
                StageOutcome(
                    ExecutionStage.CAPTURE,
                    StageStatus.FAILED,
                    stageStarted,
                    wallClock.instant().toString(),
                    error.message ?: "Capture failed.",
                )
            state.observations += HostObservation(wallClock.instant().toString(), "capture", error.message ?: "Capture failed.")
            null
        }
    }

    private fun finalizeBundle(
        request: SmokeRunRequest,
        runDirectory: Path,
        workDirectory: Path,
        executionId: String,
        accepted: AcceptedInputs,
        binding: ArtifactBindingDocument,
        bindingState: ArtifactBindingState?,
        assertionAttempt: UiAssertionAttempt?,
        captureResult: CaptureResult?,
        networkEvaluation: NetworkEvaluationDocument?,
        networkFiles: List<EvidenceFileInput>,
        networkEvents: List<TimelineEvent>,
        environmentEvaluation: EmulatorEnvironmentEvaluationV1?,
        environmentTransaction: EnvironmentTransactionDocument?,
        state: MutableExecutionState,
    ): SmokeRunResult {
        val finalizationStarted = wallClock.instant().toString()
        val hierarchy = assertionAttempt?.hierarchySource
        val screenshot = captureResult?.files?.singleOrNull { it.destination.value == SCREENSHOT_PATH.value }?.source
        val finalBindingMatches =
            bindingState?.afterCapture?.sha256 != null &&
                bindingState.afterCapture.sha256 == accepted.artifact.sha256
        val evaluated = assertionAttempt?.document?.outcome in setOf(AssertionOutcome.MATCHED, AssertionOutcome.NOT_MATCHED)
        val environmentComplete =
            accepted.environment == null || environmentEvaluation?.outcome == EnvironmentEvaluationOutcome.MATCHED
        val environmentRestored =
            (environmentTransaction?.restorationOutcome ?: EnvironmentRestorationOutcome.NOT_REQUIRED) in
                setOf(EnvironmentRestorationOutcome.NOT_REQUIRED, EnvironmentRestorationOutcome.RESTORED)
        val completeness =
            if (
                evaluated && hierarchy != null && screenshot != null && finalBindingMatches && environmentComplete &&
                environmentRestored &&
                networkEvaluation?.outcome != NetworkEvaluationOutcome.NOT_EVALUATED
            ) {
                EvidenceCompleteness.COMPLETE
            } else {
                EvidenceCompleteness.PARTIAL
            }
        val status =
            when {
                state.cancelled -> ExecutionStatus.CANCELLED
                state.primaryError != null -> ExecutionStatus.ERROR
                else -> ExecutionStatus.COMPLETED
            }
        val verdict =
            when {
                status != ExecutionStatus.COMPLETED -> ScenarioVerdict.NOT_EVALUATED
                assertionAttempt?.document?.outcome == AssertionOutcome.MATCHED &&
                    (networkEvaluation == null || networkEvaluation.outcome == NetworkEvaluationOutcome.MATCHED) ->
                    ScenarioVerdict.PASSED
                assertionAttempt?.document?.outcome == AssertionOutcome.NOT_MATCHED -> ScenarioVerdict.FAILED
                networkEvaluation?.outcome == NetworkEvaluationOutcome.MISMATCHED -> ScenarioVerdict.FAILED
                else -> ScenarioVerdict.NOT_EVALUATED
            }
        val optimisticFinalStage =
            StageOutcome(ExecutionStage.FINALIZATION, StageStatus.SUCCEEDED, finalizationStarted, wallClock.instant().toString())
        state.stages += optimisticFinalStage
        val assertionDocument =
            assertionAttempt?.document
                ?: AssertionDocument(
                    AssertionOutcome.NOT_EVALUATED,
                    accepted.scenario.scenario.expectedPackage,
                    accepted.scenario.scenario.orderedSteps.filterIsInstance<AssertUiNode>().last().resourceId,
                    accepted.scenario.scenario.orderedSteps.filterIsInstance<AssertUiNode>().last().text,
                    detail = state.primaryError ?: "Assertion was not reached.",
                )
        val resultDocument =
            ExecutionResultDocument(
                executionId = executionId,
                hostStartedAt = state.startedAt,
                hostEndedAt = wallClock.instant().toString(),
                status = status,
                verdict = verdict,
                evidenceCompleteness = completeness,
                steps = state.steps.toList(),
                stages = state.stages.toList(),
                observations = state.observations.toList(),
                assertion = assertionDocument,
                network = networkEvaluation,
                primaryError = state.primaryError,
            )

        return try {
            val sources =
                writeSupportingDocuments(
                    workDirectory,
                    accepted,
                    binding,
                    resultDocument,
                    state.stepFiles,
                    networkFiles,
                    captureResult,
                    environmentEvaluation,
                    environmentTransaction,
                )
            val manifest =
                manifest(request, executionId, accepted, binding, bindingState, captureResult, environmentEvaluation, resultDocument)
            val bundle = runDirectory.resolve("bundle")
            publisher.publish(
                EvidenceBundleRequestV3(
                    manifest,
                    timeline(resultDocument, networkEvents, environmentEvaluation, environmentTransaction),
                    sources,
                ),
                bundle,
            )
            val verification = verifier.verify(bundle)
            SmokeRunResult(bundle, null, resultDocument, verification.isValid)
        } catch (error: Exception) {
            state.stages[state.stages.lastIndex] =
                optimisticFinalStage.copy(
                    status = StageStatus.FAILED,
                    endedAt = wallClock.instant().toString(),
                    detail = error.message ?: "Evidence finalization failed.",
                )
            val failedDocument =
                resultDocument.copy(
                    hostEndedAt = wallClock.instant().toString(),
                    status = if (state.cancelled) ExecutionStatus.CANCELLED else ExecutionStatus.ERROR,
                    verdict = if (state.primaryError == null) ScenarioVerdict.NOT_EVALUATED else resultDocument.verdict,
                    stages = state.stages.toList(),
                    finalizationError = error.message ?: "Evidence finalization failed.",
                )
            val diagnostic =
                writeDiagnostic(runDirectory, executionId, state, error.message ?: "Evidence finalization failed.", failedDocument)
            SmokeRunResult(null, diagnostic, failedDocument, false)
        }
    }

    private fun writeSupportingDocuments(
        workDirectory: Path,
        accepted: AcceptedInputs,
        binding: ArtifactBindingDocument,
        result: ExecutionResultDocument,
        stepFiles: List<EvidenceFileInput>,
        networkFiles: List<EvidenceFileInput>,
        captureResult: CaptureResult?,
        environmentEvaluation: EmulatorEnvironmentEvaluationV1?,
        environmentTransaction: EnvironmentTransactionDocument?,
    ): List<EvidenceFileInput> {
        val documents = Files.createDirectories(workDirectory.resolve("documents"))
        val scenario = documents.resolve("scenario.json").also { Files.write(it, accepted.scenario.exactBytes) }
        val resultFile = documents.resolve("result.json").also { writeJson(it, result) }
        val bindingFile = documents.resolve("artifact-binding.json").also { writeJson(it, binding) }
        val files =
            mutableListOf(
                EvidenceFileInput(scenario, SCENARIO_PATH, "application/json", EvidenceFileRole.ATTACHMENT),
                EvidenceFileInput(resultFile, RESULT_PATH, "application/json", EvidenceFileRole.TEST_RESULT),
                EvidenceFileInput(bindingFile, BINDING_PATH, "application/json", EvidenceFileRole.ATTACHMENT),
            )
        files += stepFiles
        files += networkFiles
        accepted.environment?.let { environment ->
            val contract = documents.resolve("environment-contract.json").also { Files.write(it, environment.exactBytes) }
            files += EvidenceFileInput(contract, ENVIRONMENT_CONTRACT_PATH, "application/json", EvidenceFileRole.ATTACHMENT)
            environmentEvaluation?.let { evaluation ->
                val evaluationFile = documents.resolve("environment-evaluation.json").also { writeJson(it, evaluation) }
                files += EvidenceFileInput(evaluationFile, ENVIRONMENT_EVALUATION_PATH, "application/json", EvidenceFileRole.TEST_RESULT)
            }
            environmentTransaction?.let { transaction ->
                val transactionFile = documents.resolve("environment-transaction.json").also { writeJson(it, transaction) }
                files += EvidenceFileInput(transactionFile, ENVIRONMENT_TRANSACTION_PATH, "application/json", EvidenceFileRole.TEST_RESULT)
            }
        }
        captureResult?.let { captured ->
            files +=
                EvidenceFileInput(
                    captured.directory.resolve("capture.json"),
                    CAPTURE_PATH,
                    "application/json",
                    EvidenceFileRole.ATTACHMENT,
                )
            captured.files.forEach {
                files += EvidenceFileInput(it.source, it.destination, it.mediaType, it.role)
            }
        }
        return files
    }

    private fun manifest(
        request: SmokeRunRequest,
        executionId: String,
        accepted: AcceptedInputs,
        binding: ArtifactBindingDocument,
        bindingState: ArtifactBindingState?,
        captureResult: CaptureResult?,
        environmentEvaluation: EmulatorEnvironmentEvaluationV1?,
        result: ExecutionResultDocument,
    ): EvidenceBundleManifestV3 {
        val bindingStatus =
            when {
                bindingState?.afterCapture?.sha256 == accepted.artifact.sha256 -> ArtifactBindingStatus.MATCHED_BEFORE_AND_AFTER
                bindingState?.afterCapture?.sha256 != null -> ArtifactBindingStatus.MATCHED_BEFORE_FINAL_MISMATCH
                bindingState != null -> ArtifactBindingStatus.MATCHED_BEFORE_FINAL_CHECK_UNAVAILABLE
                else -> ArtifactBindingStatus.NOT_ESTABLISHED
            }
        val metadata = captureResult?.document?.metadata.orEmpty()

        fun observed(field: String): ObservedValue {
            val item = metadata[field]
            return if (item?.value != null) {
                ObservedValue(value = item.value)
            } else {
                ObservedValue(unavailableReason = item?.reason ?: "This observation was not collected.")
            }
        }
        val hierarchyPath = result.assertion.hierarchyPath
        val contractRequested = accepted.environment != null
        val localeAbsentReason =
            if (contractRequested) {
                "Locale was not observed because environment evaluation did not complete."
            } else {
                "Locale was not observed because no environment contract was requested."
            }
        val orientationAbsentReason =
            if (contractRequested) {
                "Orientation was not observed because environment evaluation did not complete."
            } else {
                "Orientation was not observed because no environment contract was requested."
            }
        return EvidenceBundleManifestV3(
            schemaVersion = V3_SCHEMA_VERSION,
            bundleId = BundleId("${accepted.scenario.scenario.scenarioId.value}-$executionId"),
            createdAt = UtcTimestamp(result.hostStartedAt),
            artifact = AndroidArtifactIdentity(AndroidArtifactType.APK, accepted.artifact.sha256),
            artifactBinding =
                ArtifactBindingSummary(
                    accepted.scenario.scenario.expectedPackage,
                    accepted.artifact.sha256,
                    binding.beforeLaunch?.sha256,
                    binding.afterCapture?.sha256,
                    bindingStatus,
                    BINDING_PATH,
                ),
            scenario = ScenarioIdentity(accepted.scenario.scenario.scenarioId, accepted.scenario.sha256),
            requestedConfiguration =
                RequestedExecutionConfiguration(
                    request.deviceSerial,
                    accepted.scenario.scenario.expectedPackage,
                    primaryUserOnly = true,
                    replaceExisting = request.replaceExisting,
                ),
            observedEnvironment =
                ObservedExecutionEnvironment(
                    observed("buildFingerprint"),
                    observed("apiLevel"),
                    manifestObservation(environmentEvaluation?.observed?.locale, localeAbsentReason),
                    manifestObservation(environmentEvaluation?.observed?.orientation, orientationAbsentReason),
                    manifestAnimations(environmentEvaluation, contractRequested),
                    ObservedValue(unavailableReason = "No random seed was requested or observed."),
                    ObservedValue(unavailableReason = "The application clock was not controlled or observed."),
                ),
            execution =
                ExecutionSummary(
                    result.status,
                    result.verdict,
                    result.evidenceCompleteness,
                    RESULT_PATH,
                    hierarchyPath,
                ),
            droidProofVersion = request.droidProofVersion,
        )
    }

    private fun timeline(
        result: ExecutionResultDocument,
        networkEvents: List<TimelineEvent>,
        environmentEvaluation: EmulatorEnvironmentEvaluationV1?,
        environmentTransaction: EnvironmentTransactionDocument?,
    ): List<TimelineEvent> =
        result.stages.flatMapIndexed { stageIndex, stage ->
            val stageNumber = (stageIndex + 1).toString().padStart(3, '0')
            val evidence =
                when (stage.stage) {
                    ExecutionStage.ENVIRONMENT ->
                        environmentEvaluation?.let {
                            listOf(EvidenceReference(ENVIRONMENT_EVALUATION_PATH, "application/json"))
                        }.orEmpty()
                    ExecutionStage.ENVIRONMENT_RESTORE ->
                        environmentTransaction?.let {
                            listOf(EvidenceReference(ENVIRONMENT_TRANSACTION_PATH, "application/json"))
                        }.orEmpty()
                    ExecutionStage.ASSERTION ->
                        result.assertion.hierarchyPath?.let {
                            listOf(
                                EvidenceReference(it, "application/xml"),
                            )
                        }.orEmpty()
                    ExecutionStage.CAPTURE ->
                        if (result.evidenceCompleteness == EvidenceCompleteness.COMPLETE) {
                            listOf(EvidenceReference(SCREENSHOT_PATH, "image/png"))
                        } else {
                            emptyList()
                        }
                    ExecutionStage.FINALIZATION -> listOf(EvidenceReference(RESULT_PATH, "application/json"))
                    else -> emptyList()
                }
            val stepEvents =
                if (stage.stage == ExecutionStage.ASSERTION) {
                    result.steps.map { step ->
                        TimelineEvent(
                            EventId("$stageNumber-${step.index.toString().padStart(3, '0')}-step"),
                            UtcTimestamp(step.hostEndedAt),
                            EventSource.HOST,
                            step.type.timelineEventType,
                            attributes = mapOf("index" to step.index.toString(), "status" to step.status.name),
                            evidence = step.hierarchyPath?.let { listOf(EvidenceReference(it, "application/xml")) }.orEmpty(),
                        )
                    }
                } else {
                    emptyList()
                }
            stepEvents +
                TimelineEvent(
                    EventId("$stageNumber-${stage.stage.name.lowercase()}"),
                    UtcTimestamp(stage.endedAt),
                    EventSource.HOST,
                    "execution.${stage.stage.name.lowercase()}",
                    attributes = mapOf("status" to stage.status.name),
                    evidence = evidence,
                )
        } + networkEvents

    private fun manifestObservation(
        observation: EnvironmentObservation?,
        absentReason: String,
    ): ObservedValue {
        val value = observation?.normalizedValue
        return if (value != null) {
            ObservedValue(value = value)
        } else {
            ObservedValue(unavailableReason = observation?.unavailableReason ?: absentReason)
        }
    }

    private fun manifestAnimations(
        evaluation: EmulatorEnvironmentEvaluationV1?,
        contractRequested: Boolean,
    ): ObservedValue {
        val animations =
            evaluation?.observed?.animations
                ?: return ObservedValue(
                    unavailableReason =
                        if (contractRequested) {
                            "Animation scales were not observed because environment evaluation did not complete."
                        } else {
                            "Animation scales were not observed because no environment contract was requested."
                        },
                )
        val values = listOf(animations.windowScale, animations.transitionScale, animations.animatorScale)
        if (values.any { it.normalizedValue == null }) {
            return ObservedValue(unavailableReason = "One or more required animation-scale observations were unavailable.")
        }
        return ObservedValue(
            value =
                "window=${animations.windowScale.normalizedValue}, " +
                    "transition=${animations.transitionScale.normalizedValue}, animator=${animations.animatorScale.normalizedValue}",
        )
    }

    private fun operationTimeout(
        request: SmokeRunRequest,
        state: MutableExecutionState,
    ): Long {
        ensureActive()
        val deadline = state.overallDeadlineNanos ?: throw RunAbort("Overall execution deadline is unavailable.")
        val remainingNanos = deadline - monotonicClock.nanoTime()
        if (remainingNanos <= 0) throw RunAbort("Overall execution deadline expired.")
        return minOf(request.commandTimeoutMillis, maxOf(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)))
    }

    private fun ensureActive() {
        if (cancellation.isCancelled()) throw RunAbort("Execution was cancelled.", true)
    }

    private fun abort(
        detail: String,
        failure: DeviceFailureKind?,
    ): Nothing = throw RunAbort(detail, failure == DeviceFailureKind.CANCELLED)

    private fun <T> stage(
        state: MutableExecutionState,
        executionStage: ExecutionStage,
        action: () -> T,
    ): T? {
        val started = wallClock.instant().toString()
        return try {
            action().also {
                state.stages += StageOutcome(executionStage, StageStatus.SUCCEEDED, started, wallClock.instant().toString())
            }
        } catch (error: RunAbort) {
            state.recordFailure(error.message ?: "Execution stage failed.", error.cancelled)
            state.stages +=
                StageOutcome(
                    executionStage,
                    if (error.cancelled) StageStatus.CANCELLED else StageStatus.FAILED,
                    started,
                    wallClock.instant().toString(),
                    error.message,
                )
            null
        } catch (error: Exception) {
            state.recordFailure(error.message ?: "Execution stage failed.", false)
            state.stages +=
                StageOutcome(
                    executionStage,
                    StageStatus.FAILED,
                    started,
                    wallClock.instant().toString(),
                    error.message,
                )
            null
        }
    }

    private fun writeDiagnostic(
        runDirectory: Path,
        executionId: String,
        state: MutableExecutionState,
        detail: String,
        result: ExecutionResultDocument? = null,
    ): Path {
        val path = runDirectory.resolve("failed-run.json")
        writeJson(
            path,
            FailedRunDiagnostic(
                executionId,
                state.startedAt,
                wallClock.instant().toString(),
                state.primaryError,
                detail,
                result,
            ),
        )
        return path
    }

    private inline fun <reified T> writeJson(
        path: Path,
        value: T,
    ) {
        Files.writeString(path, hostJson.encodeToString(value) + "\n")
    }

    private fun deleteWorkDirectory(workDirectory: Path) {
        Files.walk(workDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private data class AcceptedInputs(
        val scenario: AcceptedScenario,
        val artifact: StagedArtifact,
        val environment: AcceptedEnvironmentContract?,
    )

    private class RunAbort(
        message: String,
        val cancelled: Boolean = false,
    ) : RuntimeException(message)

    private class MutableExecutionState(
        val startedAt: String,
        private val now: () -> String,
    ) {
        val stages = mutableListOf<StageOutcome>()
        val steps = mutableListOf<StepOutcome>()
        val stepFiles = mutableListOf<EvidenceFileInput>()
        var assertionAttempt: UiAssertionAttempt? = null
        val observations = mutableListOf<HostObservation>()
        var overallDeadlineNanos: Long? = null
        var primaryError: String? = null
        var cancelled: Boolean = false

        fun canUseDevice(): Boolean = primaryError == null && !cancelled

        fun recordFailure(
            detail: String,
            wasCancelled: Boolean,
        ) {
            if (primaryError == null) primaryError = detail
            cancelled = cancelled || wasCancelled
            observations += HostObservation(now(), "failure", detail)
        }

        fun failAfterStage(
            detail: String,
            wasCancelled: Boolean = false,
        ) = recordFailure(detail, wasCancelled)

        fun skip(
            stage: ExecutionStage,
            detail: String = "A prior fatal stage prevented device actions.",
        ) {
            val timestamp = now()
            stages += StageOutcome(stage, StageStatus.SKIPPED, timestamp, timestamp, detail)
        }
    }

    private companion object {
        const val MAX_DEVICE_OPERATIONS = 12L
        const val FINALIZATION_BUDGET_MILLIS = 15_000L
        const val MAX_OVERALL_BUDGET_MILLIS = 3_600_000L
        val SAFE_RUN_ID = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        val SCENARIO_PATH = BundleRelativePath("scenario/scenario.json")
        val RESULT_PATH = BundleRelativePath("execution/result.json")
        val BINDING_PATH = BundleRelativePath("execution/artifact-binding.json")
        val ENVIRONMENT_CONTRACT_PATH = BundleRelativePath("environment/contract.json")
        val ENVIRONMENT_EVALUATION_PATH = BundleRelativePath("environment/evaluation.json")
        val ENVIRONMENT_TRANSACTION_PATH = BundleRelativePath("environment/transaction.json")
        val HIERARCHY_PATH = BundleRelativePath("ui/hierarchy.xml")
        val CAPTURE_PATH = BundleRelativePath("capture/capture.json")
        val SCREENSHOT_PATH = BundleRelativePath("screenshots/display.png")
        val FATAL_CAPTURE_ISSUES =
            setOf(
                CollectionIssueCode.DISCONNECTED,
                CollectionIssueCode.OFFLINE,
                CollectionIssueCode.UNAUTHORIZED,
                CollectionIssueCode.TIMEOUT,
                CollectionIssueCode.INTERRUPTED,
            )
    }
}

@Serializable
private data class FailedRunDiagnostic(
    val executionId: String,
    val hostStartedAt: String,
    val hostEndedAt: String,
    val primaryError: String?,
    val publicationError: String,
    val result: ExecutionResultDocument?,
)

@OptIn(ExperimentalSerializationApi::class)
private val hostJson =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
    }
