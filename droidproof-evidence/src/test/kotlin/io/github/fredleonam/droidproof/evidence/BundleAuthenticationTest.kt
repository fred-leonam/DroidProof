package io.github.fredleonam.droidproof.evidence

import io.github.fredleonam.droidproof.model.AndroidArtifactIdentity
import io.github.fredleonam.droidproof.model.AndroidArtifactType
import io.github.fredleonam.droidproof.model.ArtifactBindingStatus
import io.github.fredleonam.droidproof.model.ArtifactBindingSummary
import io.github.fredleonam.droidproof.model.BundleId
import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.DroidProofVersion
import io.github.fredleonam.droidproof.model.EvidenceBundleManifestV3
import io.github.fredleonam.droidproof.model.EvidenceCompleteness
import io.github.fredleonam.droidproof.model.ExecutionStatus
import io.github.fredleonam.droidproof.model.ExecutionSummary
import io.github.fredleonam.droidproof.model.ObservedExecutionEnvironment
import io.github.fredleonam.droidproof.model.ObservedValue
import io.github.fredleonam.droidproof.model.RequestedExecutionConfiguration
import io.github.fredleonam.droidproof.model.ScenarioId
import io.github.fredleonam.droidproof.model.ScenarioIdentity
import io.github.fredleonam.droidproof.model.ScenarioVerdict
import io.github.fredleonam.droidproof.model.Sha256
import io.github.fredleonam.droidproof.model.UtcTimestamp
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundleAuthenticationTest {
    @TempDir
    lateinit var directory: Path

    private val verifier = EvidenceBundleVerifier()
    private val keys: KeyPair = KeyPairGenerator.getInstance(AUTHENTICATION_ALGORITHM).generateKeyPair()

    @Test
    fun `unsigned legacy bundle remains integrity-valid and explicitly unsigned`() {
        val bundle = writeBundle("unsigned")

        val result = verifier.verify(bundle)

        assertTrue(result.isValid)
        assertEquals(AuthenticationStatus.UNSIGNED, result.authentication.status)
        val required = verifier.verify(bundle, keys.public)
        assertTrue(required.isValid)
        assertEquals(AuthenticationStatus.INVALID, required.authentication.status)
        assertAuthIssue(required, AuthenticationIssueCode.AUTHENTICATION_REQUIRED)
    }

    @Test
    fun `signed schema v3 bundle is untrusted without a key and authenticated with its external key`() {
        val bundle = writeBundle("signed", signing(keys))

        val untrusted = verifier.verify(bundle)
        val authenticated = verifier.verify(bundle, keys.public)

        assertTrue(untrusted.isValid)
        assertEquals(AuthenticationStatus.SIGNED_UNTRUSTED, untrusted.authentication.status)
        assertEquals(AuthenticationStatus.AUTHENTICATED, authenticated.authentication.status)
        assertEquals(AUTHENTICATION_ALGORITHM, authenticated.authentication.algorithm)
        assertEquals(BundleAuthenticator.keyId(keys.public), authenticated.authentication.keyId)
        assertTrue(authenticated.authentication.isAuthenticated)
    }

    @Test
    fun `wrong external key corrupted signature and key-id mismatch fail closed`() {
        val wrongKeys = KeyPairGenerator.getInstance(AUTHENTICATION_ALGORITHM).generateKeyPair()
        val wrongKeyBundle = writeBundle("wrong-key", signing(keys))
        val wrongKey = verifier.verify(wrongKeyBundle, wrongKeys.public)
        assertEquals(AuthenticationStatus.INVALID, wrongKey.authentication.status)
        assertAuthIssue(wrongKey, AuthenticationIssueCode.TRUSTED_KEY_ID_MISMATCH)

        val corruptBundle = writeBundle("corrupt-signature", signing(keys))
        updateEnvelope(corruptBundle) { envelope ->
            val bytes = Base64.getDecoder().decode(envelope.signature)
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            envelope.copy(signature = Base64.getEncoder().encodeToString(bytes))
        }
        val corrupt = verifier.verify(corruptBundle, keys.public)
        assertEquals(AuthenticationStatus.INVALID, corrupt.authentication.status)
        assertAuthIssue(corrupt, AuthenticationIssueCode.INVALID_SIGNATURE)

        val keyIdBundle = writeBundle("key-id", signing(keys))
        updateEnvelope(keyIdBundle) { it.copy(keyId = Sha256("0".repeat(64))) }
        val keyId = verifier.verify(keyIdBundle, keys.public)
        assertEquals(AuthenticationStatus.INVALID, keyId.authentication.status)
        assertAuthIssue(keyId, AuthenticationIssueCode.TRUSTED_KEY_ID_MISMATCH)
    }

    @Test
    fun `malformed and unknown authentication JSON are invalid rather than ignored`() {
        val malformedBundle = writeBundle("malformed", signing(keys))
        malformedBundle.resolve(AUTHENTICITY_FILE).writeText("{")
        assertAuthIssue(verifier.verify(malformedBundle), AuthenticationIssueCode.MALFORMED_AUTHENTICATION_JSON)

        val unknownBundle = writeBundle("unknown", signing(keys))
        val authentication = unknownBundle.resolve(AUTHENTICITY_FILE)
        authentication.writeText(authentication.readText().replaceFirst("{", "{\n  \"unknown\": true,"))
        assertAuthIssue(verifier.verify(unknownBundle), AuthenticationIssueCode.MALFORMED_AUTHENTICATION_JSON)
    }

    @Test
    fun `any exact-byte modification of manifest or timeline breaks authentication`() {
        val manifestBundle = writeBundle("manifest-byte-change", signing(keys))
        manifestBundle.resolve(MANIFEST_FILE).writeText(manifestBundle.resolve(MANIFEST_FILE).readText() + " \n")
        val manifestResult = verifier.verify(manifestBundle, keys.public)
        assertTrue(manifestResult.isValid)
        assertAuthIssue(manifestResult, AuthenticationIssueCode.AUTHENTICATED_CORE_SIZE_MISMATCH)

        val timelineBundle = writeBundle("timeline-byte-change", signing(keys))
        timelineBundle.resolve(TIMELINE_FILE).writeText(timelineBundle.resolve(TIMELINE_FILE).readText() + " \n")
        val timelineResult = verifier.verify(timelineBundle, keys.public)
        assertTrue(timelineResult.isValid)
        assertAuthIssue(timelineResult, AuthenticationIssueCode.AUTHENTICATED_CORE_SIZE_MISMATCH)

        val reformattedBundle = writeBundle("timeline-reformatted", signing(keys))
        val timeline = reformattedBundle.resolve(TIMELINE_FILE)
        timeline.writeText(Json.encodeToString(Json.parseToJsonElement(timeline.readText())) + "\n")
        val reformatted = verifier.verify(reformattedBundle, keys.public)
        assertTrue(reformatted.isValid)
        assertAuthIssue(reformatted, AuthenticationIssueCode.AUTHENTICATED_CORE_SIZE_MISMATCH)
    }

    @Test
    fun `rewriting evidence and manifest without signing key can restore integrity but not authenticity`() {
        val bundle = writeBundle("rewritten", signing(keys))
        val evidencePath = bundle.resolve(RESULT_PATH.value)
        evidencePath.writeText("{\"forged\":true}\n")
        val manifestPath = bundle.resolve(MANIFEST_FILE)
        val manifest = evidenceJson.decodeFromString<EvidenceBundleManifestV3>(manifestPath.readText())
        val rewrittenInventory =
            manifest.evidenceFiles.map { descriptor ->
                if (descriptor.path == RESULT_PATH) {
                    descriptor.copy(
                        sha256 = Sha256Calculator.calculate(evidencePath),
                        byteSize = Files.size(evidencePath),
                    )
                } else {
                    descriptor
                }
            }
        manifestPath.writeText(evidenceJson.encodeToString(manifest.copy(evidenceFiles = rewrittenInventory)) + "\n")

        val result = verifier.verify(bundle, keys.public)

        assertTrue(result.isValid)
        assertFalse(result.authentication.isAuthenticated)
        assertAuthIssue(result, AuthenticationIssueCode.AUTHENTICATED_CORE_SIZE_MISMATCH)
    }

    @Test
    fun `authentication envelope rejects symbolic links and non-regular files`() {
        val symlinkBundle = writeBundle("auth-symlink")
        val external = directory.resolve("external-auth.json").also { it.writeText("{}") }
        Files.createSymbolicLink(symlinkBundle.resolve(AUTHENTICITY_FILE), external)
        assertAuthIssue(verifier.verify(symlinkBundle), AuthenticationIssueCode.AUTHENTICATION_SYMBOLIC_LINK)

        val directoryBundle = writeBundle("auth-directory")
        Files.createDirectory(directoryBundle.resolve(AUTHENTICITY_FILE))
        assertAuthIssue(verifier.verify(directoryBundle), AuthenticationIssueCode.AUTHENTICATION_NON_REGULAR_FILE)
    }

    @Test
    fun `key loader accepts bounded DER and PEM keys and rejects symbolic links`() {
        val privateDer = directory.resolve("private.der").also { Files.write(it, keys.private.encoded) }
        val publicDer = directory.resolve("public.der").also { Files.write(it, keys.public.encoded) }
        assertContentEquals(keys.private.encoded, Ed25519KeyLoader.loadPrivateKey(privateDer).encoded)
        assertContentEquals(keys.public.encoded, Ed25519KeyLoader.loadPublicKey(publicDer).encoded)

        val privatePem = directory.resolve("private.pem").also { writePem(it, "PRIVATE KEY", keys.private.encoded) }
        val publicPem = directory.resolve("public.pem").also { writePem(it, "PUBLIC KEY", keys.public.encoded) }
        assertContentEquals(keys.private.encoded, Ed25519KeyLoader.loadPrivateKey(privatePem).encoded)
        assertContentEquals(keys.public.encoded, Ed25519KeyLoader.loadPublicKey(publicPem).encoded)

        val link = directory.resolve("public-link.pem")
        Files.createSymbolicLink(link, publicPem)
        assertFailsWith<IllegalArgumentException> { Ed25519KeyLoader.loadPublicKey(link) }
        assertFailsWith<IllegalArgumentException> { Ed25519KeyLoader.loadPublicKey(directory) }
    }

    private fun writeBundle(
        name: String,
        signing: BundleSigningConfiguration? = null,
    ): Path {
        val result = directory.resolve("$name-result.json").also { it.writeText("{}\n") }
        val binding = directory.resolve("$name-binding.json").also { it.writeText("{}\n") }
        return directory.resolve("$name-bundle").also { bundle ->
            EvidenceBundleWriter().write(
                EvidenceBundleRequestV3(
                    manifest(),
                    emptyList(),
                    listOf(
                        EvidenceFileInput(result, RESULT_PATH, "application/json"),
                        EvidenceFileInput(binding, BINDING_PATH, "application/json"),
                    ),
                ),
                bundle,
                signing = signing,
            )
        }
    }

    private fun manifest(): EvidenceBundleManifestV3 {
        val unavailable = ObservedValue(unavailableReason = "Not collected.")
        val digest = Sha256("a".repeat(64))
        return EvidenceBundleManifestV3(
            3,
            BundleId("authenticated-smoke-run"),
            UtcTimestamp("2026-09-15T12:00:00Z"),
            AndroidArtifactIdentity(AndroidArtifactType.APK, digest),
            ArtifactBindingSummary(
                "io.droidproof.smoke",
                digest,
                status = ArtifactBindingStatus.NOT_ESTABLISHED,
                detailPath = BINDING_PATH,
            ),
            ScenarioIdentity(ScenarioId("authenticated-smoke"), Sha256("b".repeat(64))),
            RequestedExecutionConfiguration("emulator-5554", "io.droidproof.smoke", true, false),
            ObservedExecutionEnvironment(
                unavailable,
                unavailable,
                unavailable,
                unavailable,
                unavailable,
                unavailable,
                unavailable,
            ),
            ExecutionSummary(
                ExecutionStatus.ERROR,
                ScenarioVerdict.NOT_EVALUATED,
                EvidenceCompleteness.PARTIAL,
                RESULT_PATH,
            ),
            DroidProofVersion("0.1.0-SNAPSHOT"),
        )
    }

    private fun signing(keyPair: KeyPair) = BundleSigningConfiguration(keyPair.private, keyPair.public)

    private fun writePem(
        path: Path,
        label: String,
        encoded: ByteArray,
    ) {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encoded)
        path.writeText("-----BEGIN $label-----\n$body\n-----END $label-----\n")
    }

    private fun updateEnvelope(
        bundle: Path,
        update: (BundleAuthenticationEnvelope) -> BundleAuthenticationEnvelope,
    ) {
        val path = bundle.resolve(AUTHENTICITY_FILE)
        path.writeText(evidenceJson.encodeToString(update(evidenceJson.decodeFromString(path.readText()))) + "\n")
    }

    private fun assertAuthIssue(
        result: EvidenceBundleVerificationResult,
        code: AuthenticationIssueCode,
    ) {
        assertEquals(AuthenticationStatus.INVALID, result.authentication.status)
        assertTrue(result.authentication.issues.any { it.code == code }, "Expected authentication issue $code")
    }

    private companion object {
        val RESULT_PATH = BundleRelativePath("execution/result.json")
        val BINDING_PATH = BundleRelativePath("execution/artifact-binding.json")
    }
}
