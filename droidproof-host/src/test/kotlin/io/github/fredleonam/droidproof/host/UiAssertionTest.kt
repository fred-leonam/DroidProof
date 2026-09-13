package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.model.ScenarioId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiAssertionTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `matches package resource ID and text only on the same node`() {
        val parser = UiHierarchyParser()
        val matching = source("matching.xml", FakeSmokeDevice.MATCHING_XML)
        val splitAcrossNodes =
            source(
                "split.xml",
                (
                    """<hierarchy><node package="io.droidproof.smoke" """ +
                        """resource-id="io.droidproof.smoke:id/status" text="wrong"/>""" +
                        """<node package="wrong" resource-id="wrong:id/status" """ +
                        """text="DroidProof ready"/></hierarchy>"""
                ).toByteArray(),
            )

        assertTrue(parser.inspect(matching, PACKAGE, RESOURCE_ID, TEXT).matched)
        assertFalse(parser.inspect(splitAcrossNodes, PACKAGE, RESOURCE_ID, TEXT).matched)
        assertFalse(parser.inspect(matching, "other.package", RESOURCE_ID, TEXT).matched)
        assertFalse(parser.inspect(matching, PACKAGE, "io.droidproof.smoke:id/other", TEXT).matched)
    }

    @Test
    fun `rejects malformed XML DTD and bounded input`() {
        val parser = UiHierarchyParser(maxBytes = 256)

        assertFailsWith<HierarchyValidationException> {
            parser.inspect(source("malformed.xml", "<hierarchy>".toByteArray()), PACKAGE, RESOURCE_ID, TEXT)
        }
        assertFailsWith<HierarchyValidationException> {
            parser.inspect(
                source("xxe.xml", """<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><hierarchy>&e;</hierarchy>""".toByteArray()),
                PACKAGE,
                RESOURCE_ID,
                TEXT,
            )
        }
        assertFailsWith<HierarchyValidationException> {
            parser.inspect(source("large.xml", ByteArray(257) { 'x'.code.toByte() }), PACKAGE, RESOURCE_ID, TEXT)
        }
    }

    @Test
    fun `polls with injected monotonic time until a delayed match`() {
        val device = FakeSmokeDevice()
        device.dumps += DumpResponse(FakeSmokeDevice.NON_MATCHING_XML)
        device.dumps += DumpResponse(FakeSmokeDevice.MATCHING_XML)
        val clock = FakeMonotonicClock()
        var ids = 0
        val runner =
            UiAssertionRunner(
                device,
                monotonicClock = clock,
                waiter = ScenarioWaiter(clock::advanceMillis),
                cancellation = CancellationSignal { false },
                idSource = { "attempt-${++ids}" },
            )

        val result = runner.await(scenario(), "emulator-5554", directory) { it }

        assertEquals(AssertionOutcome.MATCHED, result.document.outcome)
        assertEquals(2, result.document.successfulHierarchyObservations)
        assertTrue(Files.readAllBytes(requireNotNull(result.hierarchySource)).contentEquals(FakeSmokeDevice.MATCHING_XML))
    }

    @Test
    fun `reports failed assertion only after valid nonmatches reach deadline`() {
        val device = FakeSmokeDevice()
        val clock = FakeMonotonicClock()
        var ids = 0
        val runner =
            UiAssertionRunner(
                device,
                monotonicClock = clock,
                waiter = ScenarioWaiter(clock::advanceMillis),
                cancellation = CancellationSignal { false },
                idSource = { "attempt-${++ids}" },
            )

        val result = runner.await(scenario(deadline = 250, poll = 100), "emulator-5554", directory) { it }

        assertEquals(AssertionOutcome.NOT_MATCHED, result.document.outcome)
        assertEquals(3, result.document.successfulHierarchyObservations)
        assertTrue(result.hierarchySource != null)
    }

    @Test
    fun `collection error and stale dump remain not evaluated`() {
        val failed =
            FakeSmokeDevice().apply {
                dumps += DumpResponse(failure = DeviceFailureKind.DISCONNECTED, detail = "Disconnected.")
            }
        val stale = FakeSmokeDevice().apply { dumps += DumpResponse(bytes = null) }
        var ids = 0

        fun run(device: FakeSmokeDevice) =
            UiAssertionRunner(
                device,
                cancellation = CancellationSignal { false },
                idSource = { "attempt-${++ids}" },
            ).await(scenario(), "emulator-5554", directory.resolve("run-${++ids}")) { it }

        assertEquals(AssertionOutcome.NOT_EVALUATED, run(failed).document.outcome)
        assertEquals(AssertionOutcome.NOT_EVALUATED, run(stale).document.outcome)
    }

    @Test
    fun `tap resolves exact target and computes center without overflow`() {
        val parser = UiHierarchyParser()
        for ((bounds, coordinates) in listOf(
            "[10,20][31,61]" to TapCoordinates(20, 40),
            "[0,0][1,1]" to TapCoordinates(0, 0),
            "[2147483640,2147483640][2147483647,2147483647]" to TapCoordinates(2147483643, 2147483643),
        )) {
            assertEquals(coordinates, parser.resolveTap(tapSource(bounds), PACKAGE, RESOURCE_ID))
        }
    }

    @Test
    fun `tap rejects missing ambiguous wrong package and invalid bounds`() {
        val parser = UiHierarchyParser()
        for (bounds in listOf("", "[1,2]", "[-1,0][10,20]", "[0,0][0,20]", "[3,4][2,6]", "[0,0][10,2147483648]", "[0,0][10,20]junk")) {
            assertFailsWith<HierarchyValidationException> { parser.resolveTap(tapSource(bounds), PACKAGE, RESOURCE_ID) }
        }
        for (xml in listOf(
            "<hierarchy/>",
            "<hierarchy>" + TAP_NODE + TAP_NODE + "</hierarchy>",
            "<hierarchy>" + TAP_NODE.replace("package=\"io.droidproof.smoke\"", "package=\"other.package\"") + "</hierarchy>",
            "<hierarchy>",
            "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><hierarchy>&e;</hierarchy>",
        )) {
            assertFailsWith<HierarchyValidationException> {
                parser.resolveTap(source("invalid-tap.xml", xml.toByteArray()), PACKAGE, RESOURCE_ID)
            }
        }
    }

    private fun tapSource(bounds: String): Path =
        source(
            "tap.xml",
            ("<hierarchy>" + TAP_NODE.replace("[10,20][30,60]", bounds) + "</hierarchy>").toByteArray(),
        )

    private fun scenario(
        deadline: Long = 1000,
        poll: Long = 100,
    ) = SmokeScenario(
        1,
        ScenarioId("smoke-ready"),
        PACKAGE,
        "$PACKAGE/$PACKAGE.MainActivity",
        UiExpectation(RESOURCE_ID, TEXT),
        deadline,
        poll,
    )

    private fun source(
        name: String,
        bytes: ByteArray,
    ): Path = directory.resolve(name).also { Files.write(it, bytes) }

    private companion object {
        const val TAP_NODE = """<node package="io.droidproof.smoke" resource-id="io.droidproof.smoke:id/status" bounds="[10,20][30,60]"/>"""
        const val PACKAGE = "io.droidproof.smoke"
        const val RESOURCE_ID = "$PACKAGE:id/status"
        const val TEXT = "DroidProof ready"
    }
}
