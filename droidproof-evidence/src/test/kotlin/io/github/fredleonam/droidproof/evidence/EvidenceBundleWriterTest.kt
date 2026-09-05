package io.github.fredleonam.droidproof.evidence

import io.github.fredleonam.droidproof.model.AndroidArtifactIdentity
import io.github.fredleonam.droidproof.model.AndroidArtifactType
import io.github.fredleonam.droidproof.model.AnimationConfiguration
import io.github.fredleonam.droidproof.model.BundleId
import io.github.fredleonam.droidproof.model.DeviceInformation
import io.github.fredleonam.droidproof.model.DroidProofVersion
import io.github.fredleonam.droidproof.model.EnvironmentContract
import io.github.fredleonam.droidproof.model.EventId
import io.github.fredleonam.droidproof.model.EventSource
import io.github.fredleonam.droidproof.model.EvidenceBundleManifest
import io.github.fredleonam.droidproof.model.GitCommit
import io.github.fredleonam.droidproof.model.Orientation
import io.github.fredleonam.droidproof.model.ScenarioId
import io.github.fredleonam.droidproof.model.ScenarioIdentity
import io.github.fredleonam.droidproof.model.Sha256
import io.github.fredleonam.droidproof.model.TimelineEvent
import io.github.fredleonam.droidproof.model.UtcTimestamp
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EvidenceBundleWriterTest {
    @TempDir
    lateinit var temporaryDirectory: java.nio.file.Path

    private val writer = EvidenceBundleWriter()

    @Test
    fun `writes complete human readable bundle`() {
        val destination = temporaryDirectory.resolve("proof-checkout-offline-retry")

        val result = writer.write(destination, manifest(), events())

        assertEquals(destination.toAbsolutePath(), result)
        assertTrue(destination.resolve("manifest.json").readText().contains("\n  \"schemaVersion\""))
        assertTrue(destination.resolve("timeline.json").readText().contains("order.created"))
    }

    @Test
    fun `produces identical JSON for logically identical input`() {
        val first = temporaryDirectory.resolve("one")
        val second = temporaryDirectory.resolve("two")
        val sameEventsDifferentOrder =
            events().reversed().map { event ->
                event.copy(attributes = event.attributes.entries.reversed().associate { it.toPair() })
            }

        writer.write(first, manifest(), events())
        writer.write(second, manifest(), sameEventsDifferentOrder)

        assertEquals(first.resolve("manifest.json").readText(), second.resolve("manifest.json").readText())
        assertEquals(first.resolve("timeline.json").readText(), second.resolve("timeline.json").readText())
    }

    @Test
    fun `orders equal timestamp events by stable identifier`() {
        val destination = temporaryDirectory.resolve("ordered")

        writer.write(destination, manifest(), events())

        val timeline = destination.resolve("timeline.json").readText()
        assertTrue(timeline.indexOf("event-a") < timeline.indexOf("event-b"))
    }

    @Test
    fun `writes an empty timeline`() {
        val destination = temporaryDirectory.resolve("empty")

        writer.write(destination, manifest(), emptyList())

        assertTrue(destination.resolve("timeline.json").readText().contains("\"events\": []"))
    }

    @Test
    fun `rejects duplicate event identifiers before writing`() {
        val duplicate = events().first().copy(timestamp = UtcTimestamp("2026-09-04T12:00:01Z"))

        assertFailsWith<EvidenceBundleValidationException> {
            writer.write(temporaryDirectory.resolve("duplicates"), manifest(), listOf(events().first(), duplicate))
        }
    }

    @Test
    fun `rejects an existing destination unless overwrite is explicit`() {
        val destination = temporaryDirectory.resolve("existing")
        writer.write(destination, manifest(), emptyList())

        assertFailsWith<FileAlreadyExistsException> { writer.write(destination, manifest(), emptyList()) }
        writer.write(destination, manifest(), events(), overwrite = true)
        assertTrue(destination.resolve("timeline.json").readText().contains("event-a"))
    }

    @Test
    fun `rejects parent traversal in destination`() {
        val unsafe = temporaryDirectory.resolve("safe").resolve("..").resolve("escape")

        assertFailsWith<IllegalArgumentException> { writer.write(unsafe, manifest(), emptyList()) }
        assertTrue(Files.notExists(temporaryDirectory.resolve("escape")))
    }

    private fun manifest() =
        EvidenceBundleManifest(
            schemaVersion = 2,
            bundleId = BundleId("proof-checkout-offline-retry"),
            createdAt = UtcTimestamp("2026-09-04T12:00:00Z"),
            artifact = AndroidArtifactIdentity(AndroidArtifactType.APK, Sha256("a".repeat(64))),
            gitCommit = GitCommit("abc1234"),
            scenario = ScenarioIdentity(ScenarioId("checkout-offline-retry"), Sha256("b".repeat(64))),
            environment =
                EnvironmentContract(
                    DeviceInformation("google/sdk_gphone64_arm64/emu64a:15/test", 35),
                    "en-US",
                    Orientation.PORTRAIT,
                    AnimationConfiguration(0.0, 0.0, 0.0),
                    42,
                ),
            droidProofVersion = DroidProofVersion("0.1.0"),
        )

    private fun events() =
        listOf(
            TimelineEvent(
                EventId("event-b"),
                UtcTimestamp("2026-09-04T12:00:01Z"),
                EventSource.APPLICATION,
                "order.created",
                mapOf("zeta" to "z", "alpha" to "a"),
            ),
            TimelineEvent(EventId("event-a"), UtcTimestamp("2026-09-04T12:00:01Z"), EventSource.TEST, "order.submit"),
        )
}
