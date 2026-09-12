package io.github.fredleonam.droidproof.host

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

data class UiAssertionAttempt(
    val document: AssertionDocument,
    val hierarchySource: Path? = null,
    val error: String? = null,
    val cancelled: Boolean = false,
)

class UiAssertionRunner(
    private val device: SmokeDeviceOperations,
    private val parser: UiHierarchyParser = UiHierarchyParser(),
    private val monotonicClock: MonotonicClock = SystemMonotonicClock,
    private val waiter: ScenarioWaiter = ThreadScenarioWaiter,
    private val cancellation: CancellationSignal = SystemCancellationSignal,
    private val idSource: () -> String = { UUID.randomUUID().toString() },
) {
    fun await(
        scenario: SmokeScenario,
        serial: String,
        workDirectory: Path,
        operationTimeoutMillis: (Long) -> Long,
    ): UiAssertionAttempt {
        Files.createDirectories(workDirectory)
        val deadline = monotonicClock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(scenario.assertionDeadlineMillis)
        var successfulObservations = 0
        var lastValid: Path? = null

        while (true) {
            if (cancellation.isCancelled()) return cancelled(scenario, successfulObservations, lastValid)
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) return deadlineResult(scenario, successfulObservations, lastValid)

            val id = idSource()
            require(SAFE_ID.matches(id)) { "Hierarchy attempt ID must be a safe identifier." }
            val local = workDirectory.resolve("hierarchy-$id.xml")
            val remote = "/sdcard/Download/droidproof-$id.xml"
            val dump = device.dumpHierarchy(serial, remote, local, operationTimeoutMillis(remaining), MAX_HIERARCHY_BYTES)
            if (!dump.isSuccessful) {
                Files.deleteIfExists(local)
                return error(
                    scenario,
                    successfulObservations,
                    lastValid,
                    dump.detail ?: "UI hierarchy collection failed.",
                    dump.failure == DeviceFailureKind.CANCELLED,
                )
            }

            val inspected =
                try {
                    parser.inspect(local, scenario.expectedPackage, scenario.expectedUi.resourceId, scenario.expectedUi.text)
                } catch (error: HierarchyValidationException) {
                    Files.deleteIfExists(local)
                    return error(scenario, successfulObservations, lastValid, error.message ?: "UI hierarchy was invalid.")
                }
            successfulObservations++
            lastValid?.let(Files::deleteIfExists)
            lastValid = local
            if (inspected.matched) {
                return UiAssertionAttempt(
                    AssertionDocument(
                        AssertionOutcome.MATCHED,
                        scenario.expectedPackage,
                        scenario.expectedUi.resourceId,
                        scenario.expectedUi.text,
                        HIERARCHY_BUNDLE_PATH,
                        successfulObservations,
                        inspected.detail,
                    ),
                    local,
                )
            }

            val afterObservation = remainingMillis(deadline)
            if (afterObservation <= 0) return deadlineResult(scenario, successfulObservations, lastValid)
            try {
                waiter.delay(minOf(scenario.pollIntervalMillis, afterObservation))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return cancelled(scenario, successfulObservations, lastValid)
            }
        }
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val nanos = deadlineNanos - monotonicClock.nanoTime()
        return if (nanos <= 0) 0 else maxOf(1, TimeUnit.NANOSECONDS.toMillis(nanos))
    }

    private fun deadlineResult(
        scenario: SmokeScenario,
        observations: Int,
        hierarchy: Path?,
    ): UiAssertionAttempt =
        if (observations > 0 && hierarchy != null) {
            UiAssertionAttempt(
                AssertionDocument(
                    AssertionOutcome.NOT_MATCHED,
                    scenario.expectedPackage,
                    scenario.expectedUi.resourceId,
                    scenario.expectedUi.text,
                    HIERARCHY_BUNDLE_PATH,
                    observations,
                    "The deadline expired after valid hierarchy observations without a matching node.",
                ),
                hierarchy,
            )
        } else {
            error(scenario, observations, hierarchy, "The deadline expired before a valid hierarchy observation.")
        }

    private fun cancelled(
        scenario: SmokeScenario,
        observations: Int,
        hierarchy: Path?,
    ): UiAssertionAttempt = error(scenario, observations, hierarchy, "Execution was cancelled.", true)

    private fun error(
        scenario: SmokeScenario,
        observations: Int,
        hierarchy: Path?,
        detail: String,
        cancelled: Boolean = false,
    ): UiAssertionAttempt =
        UiAssertionAttempt(
            AssertionDocument(
                AssertionOutcome.NOT_EVALUATED,
                scenario.expectedPackage,
                scenario.expectedUi.resourceId,
                scenario.expectedUi.text,
                null,
                observations,
                detail,
            ),
            hierarchySource = null,
            error = detail,
            cancelled = cancelled,
        ).also { hierarchy?.let(Files::deleteIfExists) }

    private companion object {
        const val MAX_HIERARCHY_BYTES = 2L * 1024L * 1024L
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
        val HIERARCHY_BUNDLE_PATH = io.github.fredleonam.droidproof.model.BundleRelativePath("ui/hierarchy.xml")
    }
}
