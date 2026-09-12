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
import io.github.fredleonam.droidproof.model.AndroidArtifactIdentity
import io.github.fredleonam.droidproof.model.AndroidArtifactType
import io.github.fredleonam.droidproof.model.ArtifactBindingStatus
import io.github.fredleonam.droidproof.model.ArtifactBindingSummary
import io.github.fredleonam.droidproof.model.BundleId
import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.DroidProofVersion
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
    val replaceExisting: Boolean = false,
    val droidProofVersion: DroidProofVersion,
    val commandTimeoutMillis: Long = 15_000,
) {
    init {
        require(deviceSerial.isNotBlank()) { "An explicit test-emulator serial is required." }
        require(commandTimeoutMillis in 1..3_600_000) { "Command timeout is outside supported bounds." }
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
    private val assertionRunner: UiAssertionRunner = UiAssertionRunner(device),
    private val idSource: () -> String = { UUID.randomUUID().toString() },
) {
    fun run(request: SmokeRunRequest): SmokeRunResult {
        val startedAt = wallClock.instant().toString()
        val executionId = idSource()
        require(SAFE_RUN_ID.matches(executionId)) { "Execution ID must be a lower-case slug." }
        Files.createDirectories(request.outputRoot)
        val runDirectory = Files.createTempDirectory(request.outputRoot, "$executionId-")
        val workDirectory = Files.createDirectory(runDirectory.resolve("work"))
        val state = MutableExecutionState(startedAt) { wallClock.instant().toString() }

        var accepted: AcceptedInputs? = null
        stage<Unit>(state, ExecutionStage.PREFLIGHT) {
            ensureActive()
            val scenario = SmokeScenarioLoader.load(request.scenarioPath)
            val artifact = ArtifactBinder(device, wallClock).snapshot(request.apkPath, workDirectory)
            accepted = AcceptedInputs(scenario, artifact)
            val overallBudget =
                scenario.scenario.assertionDeadlineMillis +
                    request.commandTimeoutMillis * MAX_DEVICE_OPERATIONS +
                    FINALIZATION_BUDGET_MILLIS
            state.overallDeadlineNanos =
                monotonicClock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(overallBudget.coerceAtMost(MAX_OVERALL_BUDGET_MILLIS))
            val preflight = device.preflight(request.deviceSerial, operationTimeout(request, state))
            if (!preflight.isSuccessful) abort(preflight.detail ?: "Device preflight failed.", preflight.failure)
        }
        val inputs = accepted

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

        var assertion: UiAssertionAttempt? = null
        if (state.canUseDevice() && inputs != null) {
            assertion =
                stage(state, ExecutionStage.ASSERTION) {
                    assertionRunner.await(
                        inputs.scenario.scenario,
                        request.deviceSerial,
                        workDirectory.resolve("hierarchies"),
                    ) { assertionRemaining -> minOf(assertionRemaining, operationTimeout(request, state)) }
                        .also { result ->
                            if (result.error != null) throw RunAbort(result.error, result.cancelled)
                        }
                }
        } else {
            state.skip(ExecutionStage.ASSERTION)
        }

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
                }
            }
        } else {
            state.skip(ExecutionStage.CAPTURE)
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
                state,
            )
        deleteWorkDirectory(workDirectory)
        return result
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
        state: MutableExecutionState,
    ): SmokeRunResult {
        val finalizationStarted = wallClock.instant().toString()
        val hierarchy = assertionAttempt?.hierarchySource
        val screenshot = captureResult?.files?.singleOrNull { it.destination.value == SCREENSHOT_PATH.value }?.source
        val finalBindingMatches =
            bindingState?.afterCapture?.sha256 != null &&
                bindingState.afterCapture.sha256 == accepted.artifact.sha256
        val evaluated = assertionAttempt?.document?.outcome in setOf(AssertionOutcome.MATCHED, AssertionOutcome.NOT_MATCHED)
        val completeness =
            if (evaluated && hierarchy != null && screenshot != null && finalBindingMatches) {
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
                assertionAttempt?.document?.outcome == AssertionOutcome.MATCHED -> ScenarioVerdict.PASSED
                assertionAttempt?.document?.outcome == AssertionOutcome.NOT_MATCHED -> ScenarioVerdict.FAILED
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
                    accepted.scenario.scenario.expectedUi.resourceId,
                    accepted.scenario.scenario.expectedUi.text,
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
                stages = state.stages.toList(),
                observations = state.observations.toList(),
                assertion = assertionDocument,
                primaryError = state.primaryError,
            )

        return try {
            val sources =
                writeSupportingDocuments(
                    workDirectory,
                    accepted,
                    binding,
                    resultDocument,
                    hierarchy,
                    captureResult,
                )
            val manifest =
                manifest(request, executionId, accepted, binding, bindingState, captureResult, resultDocument)
            val bundle = runDirectory.resolve("bundle")
            publisher.publish(
                EvidenceBundleRequestV3(manifest, timeline(resultDocument), sources),
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
        hierarchy: Path?,
        captureResult: CaptureResult?,
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
        hierarchy?.let { files += EvidenceFileInput(it, HIERARCHY_PATH, "application/xml", EvidenceFileRole.SEMANTICS) }
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
                    ObservedValue(unavailableReason = "Locale was not observed or controlled by this milestone."),
                    ObservedValue(unavailableReason = "Orientation was not observed or controlled by this milestone."),
                    ObservedValue(unavailableReason = "Animation scales were not observed or controlled by this milestone."),
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

    private fun timeline(result: ExecutionResultDocument): List<TimelineEvent> =
        result.stages.mapIndexed { index, stage ->
            val evidence =
                when (stage.stage) {
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
            TimelineEvent(
                EventId("%03d-%s".format(index + 1, stage.stage.name.lowercase())),
                UtcTimestamp(stage.endedAt),
                EventSource.HOST,
                "execution.${stage.stage.name.lowercase()}",
                attributes = mapOf("status" to stage.status.name),
                evidence = evidence,
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

        fun skip(stage: ExecutionStage) {
            val timestamp = now()
            stages += StageOutcome(stage, StageStatus.SKIPPED, timestamp, timestamp, "A prior fatal stage prevented device actions.")
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
