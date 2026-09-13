package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.evidence.Sha256Calculator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CheckedInInteractiveScenariosTest {
    @Test
    fun `checked-in interactive scenarios are valid schema v2 documents with the intended behavior`() {
        val passingPath = scenarioPath("interactive-passing.json")
        val failingPath = scenarioPath("interactive-failing.json")
        val passingAccepted = SmokeScenarioLoader.load(passingPath)
        val failingAccepted = SmokeScenarioLoader.load(failingPath)
        val passing = passingAccepted.scenario as SmokeScenarioV2
        val failing = failingAccepted.scenario as SmokeScenarioV2

        assertEquals(2, passing.schemaVersion)
        assertEquals(2, failing.schemaVersion)
        assertEquals(EXPECTED_PACKAGE, passing.expectedPackage)
        assertEquals(EXPECTED_PACKAGE, failing.expectedPackage)
        assertEquals(passing.launchComponent, failing.launchComponent)
        val expectedTypes =
            listOf(
                StepType.TYPE_TEXT_UI_NODE,
                StepType.TAP_UI_NODE,
                StepType.ASSERT_UI_NODE,
            )
        assertEquals(expectedTypes, passing.steps.map { it.stepType })
        assertEquals(expectedTypes, failing.steps.map { it.stepType })

        for (scenario in listOf(passing, failing)) {
            assertTrue(scenario.steps.all { it.resourceId.startsWith("${scenario.expectedPackage}:id/") })
            assertEquals(TypeTextUiNode("$EXPECTED_PACKAGE:id/name", "DroidProof42"), scenario.steps[0])
            assertEquals(TapUiNode("$EXPECTED_PACKAGE:id/action"), scenario.steps[1])
        }

        val passingAssertion = passing.steps[2] as AssertUiNode
        val failingAssertion = failing.steps[2] as AssertUiNode
        assertEquals("Hello DroidProof42", passingAssertion.text)
        assertNotEquals(passingAssertion.text, failingAssertion.text)
        assertEquals(passingAssertion.copy(text = failingAssertion.text), failingAssertion)

        assertContentEquals(Files.readAllBytes(passingPath), passingAccepted.exactBytes)
        assertContentEquals(Files.readAllBytes(failingPath), failingAccepted.exactBytes)
        assertEquals(Sha256Calculator.calculate(passingPath), passingAccepted.sha256)
        assertEquals(Sha256Calculator.calculate(failingPath), failingAccepted.sha256)
    }

    private fun scenarioPath(name: String): Path =
        Path.of(requireNotNull(System.getProperty("droidproof.repositoryRoot")))
            .resolve("samples/smoke-app/scenarios")
            .resolve(name)

    private companion object {
        const val EXPECTED_PACKAGE = "io.github.fredleonam.droidproof.smokeapp"
    }
}
