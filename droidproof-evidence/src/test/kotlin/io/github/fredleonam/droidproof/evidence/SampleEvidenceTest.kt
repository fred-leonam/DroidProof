package io.github.fredleonam.droidproof.evidence

import io.github.fredleonam.droidproof.model.DroidProofVersion
import kotlin.test.Test
import kotlin.test.assertEquals

class SampleEvidenceTest {
    @Test
    fun `synthetic manifest uses the supplied authoritative project version`() {
        val version = DroidProofVersion("7.8.9-test")

        assertEquals(version, sampleManifest(version).droidProofVersion)
    }
}
