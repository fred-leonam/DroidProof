package io.github.fredleonam.droidproof.evidence

import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.EvidenceBundleManifest
import io.github.fredleonam.droidproof.model.EvidenceBundleManifestV3
import io.github.fredleonam.droidproof.model.EvidenceFileDescriptor
import io.github.fredleonam.droidproof.model.TimelineDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.PublicKey
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.Locale

enum class VerificationSeverity {
    ERROR,
    WARNING,
}

enum class VerificationIssueCode {
    MISSING_MANIFEST,
    MISSING_TIMELINE,
    MALFORMED_JSON,
    UNSUPPORTED_SCHEMA,
    MISSING_REFERENCED_EVIDENCE_FILE,
    UNREGISTERED_TIMELINE_REFERENCE,
    DUPLICATE_INVENTORY_PATH,
    CASE_INSENSITIVE_PATH_COLLISION,
    UNSAFE_INVENTORY_PATH,
    INVENTORY_NOT_SORTED,
    FILE_SIZE_MISMATCH,
    SHA256_MISMATCH,
    MEDIA_TYPE_CONFLICT,
    UNEXPECTED_EVIDENCE_FILE,
    SYMBOLIC_LINK,
    NON_REGULAR_FILE,
    FILE_INTEGRITY_UNAVAILABLE,
    DUPLICATE_EVENT_ID,
    IO_ERROR,
}

data class VerificationIssue(
    val code: VerificationIssueCode,
    val severity: VerificationSeverity,
    val message: String,
    val path: String? = null,
)

data class EvidenceBundleVerificationResult(
    val schemaVersion: Int?,
    val issues: List<VerificationIssue>,
    val authentication: BundleAuthenticationResult = BundleAuthenticationResult(AuthenticationStatus.UNSIGNED),
) {
    val errors: List<VerificationIssue> get() = issues.filter { it.severity == VerificationSeverity.ERROR }
    val warnings: List<VerificationIssue> get() = issues.filter { it.severity == VerificationSeverity.WARNING }
    val isValid: Boolean get() = errors.isEmpty()
}

internal open class VerificationFileOperations {
    open fun size(path: Path): Long = Files.size(path)

    open fun hash(path: Path): String = Sha256Calculator.calculate(path).value

    open fun walk(path: Path): java.util.stream.Stream<Path> = Files.walk(path)
}

class EvidenceBundleVerifier internal constructor(private val files: VerificationFileOperations) {
    constructor() : this(VerificationFileOperations())

    fun verify(
        bundle: Path,
        trustedPublicKey: PublicKey? = null,
    ): EvidenceBundleVerificationResult = verify(bundle, BundleVerificationConfiguration(trustedPublicKey))

    fun verify(
        bundle: Path,
        configuration: BundleVerificationConfiguration,
    ): EvidenceBundleVerificationResult {
        val root = bundle.toAbsolutePath().normalize()
        val issues = mutableListOf<VerificationIssue>()

        fun result(schemaVersion: Int?): EvidenceBundleVerificationResult =
            EvidenceBundleVerificationResult(
                schemaVersion,
                issues,
                verifyAuthentication(root, configuration, issues.none { it.severity == VerificationSeverity.ERROR }),
            )

        if (Files.isSymbolicLink(root)) {
            issues.error(VerificationIssueCode.SYMBOLIC_LINK, "Bundle root must not be a symbolic link.")
            return result(null)
        }
        val manifestText = readCoreFile(root, MANIFEST_FILE, VerificationIssueCode.MISSING_MANIFEST, issues)
        val timelineText = readCoreFile(root, TIMELINE_FILE, VerificationIssueCode.MISSING_TIMELINE, issues)
        if (manifestText == null || timelineText == null) return result(null)

        val manifestObject =
            parseObject(manifestText, MANIFEST_FILE, issues)
                ?: return result(null)
        val timelineObject =
            parseObject(timelineText, TIMELINE_FILE, issues)
                ?: return result(manifestObject.schemaVersion())
        val schemaVersion = manifestObject.schemaVersion()
        if (schemaVersion == null) {
            issues.error(VerificationIssueCode.MALFORMED_JSON, "schemaVersion must be an integer JSON number.", MANIFEST_FILE)
            return result(null)
        }
        if (schemaVersion !in setOf(1, V2_SCHEMA_VERSION, V3_SCHEMA_VERSION)) {
            issues.error(
                VerificationIssueCode.UNSUPPORTED_SCHEMA,
                "Unsupported evidence schema version: $schemaVersion.",
                MANIFEST_FILE,
            )
            return result(schemaVersion)
        }

        var rawPaths = emptyList<String>()
        if (schemaVersion in setOf(V2_SCHEMA_VERSION, V3_SCHEMA_VERSION)) {
            val rawInventory = manifestObject["evidenceFiles"] as? JsonArray
            if (rawInventory == null) {
                issues.error(
                    VerificationIssueCode.MALFORMED_JSON,
                    "Schema version $schemaVersion requires evidenceFiles.",
                    MANIFEST_FILE,
                )
                return result(schemaVersion)
            }
            rawPaths = validateRawInventoryPaths(rawInventory, issues)
            validateInventoryOrder(rawPaths, issues)
            if (issues.any { it.code == VerificationIssueCode.UNSAFE_INVENTORY_PATH }) {
                scanUnexpectedFiles(root, rawPaths.toSet(), issues)
                return result(schemaVersion)
            }
        }

        val manifest =
            decodeManifest(schemaVersion, manifestText, issues)
                ?: return result(schemaVersion)
        val timeline =
            decodeTimeline(timelineObject, timelineText, issues)
                ?: return result(schemaVersion)
        timeline.events.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
            issues.error(VerificationIssueCode.DUPLICATE_EVENT_ID, "Timeline event ID is duplicated: $id.", TIMELINE_FILE)
        }
        if (schemaVersion == 1) {
            issues.warning(
                VerificationIssueCode.FILE_INTEGRITY_UNAVAILABLE,
                "Schema version 1 does not bind evidence files; file integrity was not verified.",
            )
            return result(schemaVersion)
        }

        val inventoryByPath = manifest.evidenceFiles.associateBy { it.path }
        val references =
            timeline.events.flatMap { event ->
                event.evidence.map { VerificationReference(it.path, it.mediaType) }
            } + manifest.references.map { VerificationReference(it) }
        references.forEach { reference ->
            val descriptor = inventoryByPath[reference.path]
            if (descriptor == null) {
                issues.error(
                    VerificationIssueCode.UNREGISTERED_TIMELINE_REFERENCE,
                    "Timeline references an unregistered evidence file.",
                    reference.path.value,
                )
            } else if (reference.mediaType != null && reference.mediaType != descriptor.mediaType) {
                issues.error(
                    VerificationIssueCode.MEDIA_TYPE_CONFLICT,
                    "Timeline and inventory media types differ.",
                    reference.path.value,
                )
            }
        }
        manifest.evidenceFiles.forEach {
            ioIssue(issues, it.path.value) { verifyEvidenceFile(root, it.path, it.byteSize, it.sha256.value, issues) }
        }
        scanUnexpectedFiles(root, rawPaths.toSet(), issues)
        return result(schemaVersion)
    }

    private fun verifyAuthentication(
        root: Path,
        configuration: BundleVerificationConfiguration,
        integrityValid: Boolean,
    ): BundleAuthenticationResult {
        val trustedPublicKey = configuration.trustedPublicKey
        val certificateConfiguration = configuration.certificate
        val path = root.resolve(AUTHENTICITY_FILE)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (certificateConfiguration?.requiresCertificatePath == true) {
                return strictCertificatePathRequired(null, certificateConfiguration)
            }
            return if (trustedPublicKey == null && certificateConfiguration?.requiresCertificatePath != true) {
                BundleAuthenticationResult(AuthenticationStatus.UNSIGNED)
            } else {
                invalid(
                    AuthenticationIssueCode.AUTHENTICATION_REQUIRED,
                    "Trusted authentication was requested but no signature is present.",
                )
            }
        }
        if (Files.isSymbolicLink(path)) {
            return invalid(
                AuthenticationIssueCode.AUTHENTICATION_SYMBOLIC_LINK,
                "Authentication envelope must not be a symbolic link.",
                AUTHENTICITY_FILE,
            )
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return invalid(
                AuthenticationIssueCode.AUTHENTICATION_NON_REGULAR_FILE,
                "Authentication envelope must be a regular file.",
                AUTHENTICITY_FILE,
            )
        }
        val text =
            try {
                val size = Files.size(path)
                if (size > MAX_AUTHENTICATION_FILE_BYTES) {
                    return invalid(
                        AuthenticationIssueCode.AUTHENTICATION_FILE_TOO_LARGE,
                        "Authentication envelope exceeds $MAX_AUTHENTICATION_FILE_BYTES bytes.",
                        AUTHENTICITY_FILE,
                    )
                }
                Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val bytes = input.readNBytes(MAX_AUTHENTICATION_FILE_BYTES.toInt() + 1)
                    if (bytes.size > MAX_AUTHENTICATION_FILE_BYTES) {
                        return invalid(
                            AuthenticationIssueCode.AUTHENTICATION_FILE_TOO_LARGE,
                            "Authentication envelope exceeds $MAX_AUTHENTICATION_FILE_BYTES bytes.",
                            AUTHENTICITY_FILE,
                        )
                    }
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                }
            } catch (error: IOException) {
                return invalid(
                    AuthenticationIssueCode.AUTHENTICATION_IO_ERROR,
                    "Authentication envelope cannot be read: ${error.message}",
                    AUTHENTICITY_FILE,
                )
            } catch (error: SecurityException) {
                return invalid(
                    AuthenticationIssueCode.AUTHENTICATION_IO_ERROR,
                    "Authentication envelope access was denied: ${error.message}",
                    AUTHENTICITY_FILE,
                )
            }
        val envelope =
            try {
                evidenceJson.decodeFromString<BundleAuthenticationEnvelope>(text)
            } catch (error: Exception) {
                return invalid(
                    AuthenticationIssueCode.MALFORMED_AUTHENTICATION_JSON,
                    "Authentication envelope is malformed: ${error.message}",
                    AUTHENTICITY_FILE,
                )
            }
        if (envelope.schemaVersion !in setOf(AUTHENTICATION_SCHEMA_VERSION, CERTIFICATE_AUTHENTICATION_SCHEMA_VERSION)) {
            return invalid(
                AuthenticationIssueCode.UNSUPPORTED_AUTHENTICATION_SCHEMA,
                "Unsupported authentication schema version: ${envelope.schemaVersion}.",
                AUTHENTICITY_FILE,
                envelope,
            )
        }
        if (envelope.algorithm != AUTHENTICATION_ALGORITHM) {
            return invalid(
                AuthenticationIssueCode.UNSUPPORTED_AUTHENTICATION_ALGORITHM,
                "Unsupported authentication algorithm: ${envelope.algorithm}.",
                AUTHENTICITY_FILE,
                envelope,
            )
        }
        if (envelope.schemaVersion == AUTHENTICATION_SCHEMA_VERSION && envelope.certificateChain != null) {
            return invalid(
                AuthenticationIssueCode.MALFORMED_AUTHENTICATION_JSON,
                "Version 1 authentication must not contain certificates.",
                AUTHENTICITY_FILE,
                envelope,
            )
        }
        if (envelope.coreFiles.map { it.path } != BundleAuthenticator.AUTHENTICATED_CORE_PATHS ||
            envelope.coreFiles.any { it.byteSize < 0 }
        ) {
            return invalid(
                AuthenticationIssueCode.INVALID_AUTHENTICATION_CORE_FILES,
                "Authentication core-file description is not canonical.",
                AUTHENTICITY_FILE,
                envelope,
            )
        }
        val signature =
            try {
                Base64.getDecoder().decode(envelope.signature).also {
                    if (it.size != ED25519_SIGNATURE_BYTES) throw IllegalArgumentException("Unexpected signature size.")
                }
            } catch (error: IllegalArgumentException) {
                return invalid(
                    AuthenticationIssueCode.MALFORMED_SIGNATURE,
                    "Authentication signature is malformed.",
                    AUTHENTICITY_FILE,
                    envelope,
                )
            }
        val actualCore =
            try {
                BundleAuthenticator.describe(root)
            } catch (error: Exception) {
                return invalid(
                    AuthenticationIssueCode.AUTHENTICATION_IO_ERROR,
                    "Authenticated core files cannot be measured: ${error.message}",
                    AUTHENTICITY_FILE,
                    envelope,
                )
            }
        envelope.coreFiles.zip(actualCore).forEach { (expected, actual) ->
            if (expected.byteSize != actual.byteSize) {
                return invalid(
                    AuthenticationIssueCode.AUTHENTICATED_CORE_SIZE_MISMATCH,
                    "Authenticated core-file byte size does not match.",
                    expected.path,
                    envelope,
                )
            }
            if (expected.sha256 != actual.sha256) {
                return invalid(
                    AuthenticationIssueCode.AUTHENTICATED_CORE_SHA256_MISMATCH,
                    "Authenticated core-file SHA-256 does not match.",
                    expected.path,
                    envelope,
                )
            }
        }
        val chain =
            if (envelope.schemaVersion == CERTIFICATE_AUTHENTICATION_SCHEMA_VERSION) {
                decodeChain(envelope) ?: return invalid(
                    AuthenticationIssueCode.CERTIFICATE_CHAIN_MALFORMED,
                    "Certificate chain is malformed.",
                    AUTHENTICITY_FILE,
                    envelope,
                )
            } else {
                emptyList()
            }
        val signingKey = chain.firstOrNull()?.publicKey ?: trustedPublicKey
        if (chain.isNotEmpty() && BundleAuthenticator.keyId(chain.first().publicKey) != envelope.keyId) {
            return invalid(
                AuthenticationIssueCode.CERTIFICATE_LEAF_KEY_MISMATCH,
                "Leaf certificate public key does not match the recorded key ID.",
                AUTHENTICITY_FILE,
                envelope,
            )
        }
        if (signingKey == null) {
            return BundleAuthenticationResult(
                AuthenticationStatus.SIGNED_UNTRUSTED,
                envelope.algorithm,
                envelope.keyId,
                signature = AuthenticationCheck.NOT_CHECKED,
            )
        }
        if (trustedPublicKey != null && BundleAuthenticator.keyId(trustedPublicKey) != envelope.keyId) {
            return invalid(
                AuthenticationIssueCode.TRUSTED_KEY_ID_MISMATCH,
                "Authentication key ID does not match the externally trusted public key.",
                AUTHENTICITY_FILE,
                envelope,
            )
        }
        val validSignature =
            try {
                BundleAuthenticator.verify(
                    signingKey,
                    if (chain.isEmpty()) {
                        BundleAuthenticator.signingMessage(envelope.coreFiles)
                    } else {
                        BundleAuthenticator.certificateSigningMessage(envelope.coreFiles, chain)
                    },
                    signature,
                )
            } catch (_: Exception) {
                false
            }
        if (!validSignature) {
            return invalid(
                AuthenticationIssueCode.INVALID_SIGNATURE,
                "Ed25519 signature verification failed.",
                AUTHENTICITY_FILE,
                envelope,
            )
        }
        if (!integrityValid) {
            return invalid(
                AuthenticationIssueCode.BUNDLE_INTEGRITY_FAILED,
                "Authentication cannot succeed because bundle integrity verification failed.",
                null,
                envelope,
            )
        }
        if (chain.isEmpty()) {
            if (certificateConfiguration?.requiresCertificatePath == true) {
                return strictCertificatePathRequired(envelope, certificateConfiguration)
            }
            return BundleAuthenticationResult(
                AuthenticationStatus.AUTHENTICATED,
                envelope.algorithm,
                envelope.keyId,
                signature = AuthenticationCheck.GOOD,
            )
        }
        val certificateResult = validateCertificateChain(chain, certificateConfiguration)
        val requireCertificatePath = certificateConfiguration?.requiresCertificatePath == true
        val failedRequired =
            (requireCertificatePath && certificateResult.chainTrust != AuthenticationCheck.GOOD) ||
                (certificateConfiguration?.requireGoodRevocation == true && certificateResult.revocation != AuthenticationCheck.GOOD)
        val strictIssues = certificateResult.issues.toMutableList()
        if (requireCertificatePath && certificateResult.chainTrust != AuthenticationCheck.GOOD) {
            strictIssues.add(
                AuthenticationIssue(
                    AuthenticationIssueCode.STRICT_CERTIFICATE_TRUST_REQUIRED,
                    "Verification policy requires a trusted certificate chain.",
                ),
            )
        }
        if (certificateConfiguration?.requireGoodRevocation == true && certificateResult.revocation != AuthenticationCheck.GOOD) {
            strictIssues.add(
                AuthenticationIssue(
                    AuthenticationIssueCode.STRICT_GOOD_REVOCATION_REQUIRED,
                    "Verification policy requires current good revocation evidence.",
                ),
            )
        }
        return BundleAuthenticationResult(
            if (failedRequired) {
                AuthenticationStatus.INVALID
            } else if (
                certificateResult.chainTrust == AuthenticationCheck.GOOD &&
                certificateResult.revocation != AuthenticationCheck.REVOKED
            ) {
                AuthenticationStatus.AUTHENTICATED
            } else {
                AuthenticationStatus.SIGNED_UNTRUSTED
            },
            envelope.algorithm,
            envelope.keyId,
            strictIssues,
            AuthenticationCheck.GOOD,
            certificateResult.chainTrust,
            certificateResult.revocation,
            certificateConfiguration?.evaluationTime,
        )
    }

    private data class CertificateResult(
        val chainTrust: AuthenticationCheck,
        val revocation: AuthenticationCheck,
        val issues: List<AuthenticationIssue>,
    )

    private fun strictCertificatePathRequired(
        envelope: BundleAuthenticationEnvelope?,
        configuration: CertificateVerificationConfiguration,
    ): BundleAuthenticationResult {
        val issues = mutableListOf<AuthenticationIssue>()
        if (envelope == null) {
            issues.add(
                AuthenticationIssue(
                    AuthenticationIssueCode.AUTHENTICATION_REQUIRED,
                    "Trusted authentication was requested but no signature is present.",
                ),
            )
        }
        issues.addAll(
            listOf(
                AuthenticationIssue(
                    AuthenticationIssueCode.CERTIFICATE_CHAIN_MISSING,
                    "Verification policy requires a version 2 certificate authentication envelope.",
                    AUTHENTICITY_FILE,
                ),
                AuthenticationIssue(
                    AuthenticationIssueCode.STRICT_CERTIFICATE_TRUST_REQUIRED,
                    "Verification policy requires a trusted certificate chain.",
                ),
            ),
        )
        if (configuration.requireGoodRevocation) {
            issues.add(
                AuthenticationIssue(
                    AuthenticationIssueCode.STRICT_GOOD_REVOCATION_REQUIRED,
                    "Verification policy requires current good revocation evidence from a trusted certificate path.",
                ),
            )
        }
        return BundleAuthenticationResult(
            AuthenticationStatus.INVALID,
            envelope?.algorithm,
            envelope?.keyId,
            issues,
            if (envelope == null) AuthenticationCheck.NOT_CHECKED else AuthenticationCheck.GOOD,
            AuthenticationCheck.FAILED,
            AuthenticationCheck.NOT_CHECKED,
            configuration.evaluationTime,
        )
    }

    private fun decodeChain(envelope: BundleAuthenticationEnvelope): List<X509Certificate>? {
        try {
            val encoded = envelope.certificateChain ?: return null
            if (encoded.isEmpty() || encoded.size > BundleAuthenticator.MAX_CERTIFICATES || encoded.any { it.length > 128 * 1024 }) {
                return null
            }
            val certificates =
                encoded.map {
                    CertificateFactory.getInstance(
                        "X.509",
                    ).generateCertificate(Base64.getDecoder().decode(it).inputStream()) as X509Certificate
                }
            if (certificates.map { Base64.getEncoder().encodeToString(it.encoded) }.toSet().size != certificates.size) return null
            certificates.zipWithNext().forEach { (child, issuer) ->
                if (child.issuerX500Principal != issuer.subjectX500Principal) return null
                child.verify(issuer.publicKey)
            }
            return certificates
        } catch (_: Exception) {
            return null
        }
    }

    private fun validateCertificateChain(
        chain: List<X509Certificate>,
        configuration: CertificateVerificationConfiguration?,
    ): CertificateResult {
        if (configuration == null || configuration.trustAnchors.isEmpty()) {
            return CertificateResult(
                AuthenticationCheck.NOT_CHECKED,
                AuthenticationCheck.NOT_CHECKED,
                listOf(
                    AuthenticationIssue(
                        AuthenticationIssueCode.CERTIFICATE_TRUST_NOT_CONFIGURED,
                        "Certificate chain is embedded input; no external trust anchors were configured.",
                        AUTHENTICITY_FILE,
                    ),
                ),
            )
        }
        val time = Date.from(configuration.evaluationTime)
        try {
            chain.forEach { it.checkValidity(time) }
            val leaf = chain.first()
            if (leaf.basicConstraints >= 0 || leaf.keyUsage?.getOrNull(0) == false) {
                throw IllegalArgumentException(
                    "Leaf does not meet producer signing policy.",
                )
            }
            chain.drop(1).forEach {
                    certificate ->
                if (certificate.basicConstraints < 0 || certificate.keyUsage?.getOrNull(5) == false) {
                    throw IllegalArgumentException(
                        "Issuer does not meet CA policy.",
                    )
                }
            }
            val anchors = configuration.trustAnchors.map { TrustAnchor(it, null) }.toSet()
            val pathCertificates =
                chain.dropLast(
                    if (configuration.trustAnchors.any { it.encoded.contentEquals(chain.last().encoded) }) 1 else 0,
                )
            val parameters =
                PKIXParameters(anchors).apply {
                    isRevocationEnabled = false
                    date = time
                }
            val validation =
                CertPathValidator.getInstance(
                    "PKIX",
                ).validate(CertificateFactory.getInstance("X.509").generateCertPath(pathCertificates), parameters)
            val anchor =
                (validation as java.security.cert.PKIXCertPathValidatorResult).trustAnchor.trustedCert
                    ?: throw IllegalArgumentException("Configured trust anchor has no certificate.")
            val validatedPath = pathCertificates + anchor
            return certificateResultWithRevocation(validatedPath, configuration)
        } catch (_: Exception) {
            return CertificateResult(
                AuthenticationCheck.FAILED,
                AuthenticationCheck.NOT_CHECKED,
                listOf(
                    AuthenticationIssue(
                        AuthenticationIssueCode.CERTIFICATE_CHAIN_UNTRUSTED,
                        "Certificate chain did not validate against configured trust anchors and producer policy.",
                        AUTHENTICITY_FILE,
                    ),
                ),
            )
        }
    }

    private fun certificateResultWithRevocation(
        validatedPath: List<X509Certificate>,
        configuration: CertificateVerificationConfiguration,
    ): CertificateResult =
        CertificateResult(
            AuthenticationCheck.GOOD,
            evaluateRevocation(validatedPath, configuration.crls, configuration.evaluationTime),
            emptyList(),
        ).let {
                result ->
            if (result.revocation == AuthenticationCheck.UNKNOWN) {
                result.copy(
                    issues =
                        listOf(
                            AuthenticationIssue(
                                AuthenticationIssueCode.REVOCATION_UNKNOWN,
                                "No current applicable offline CRL proves the certificate is not revoked at evaluation time.",
                                AUTHENTICITY_FILE,
                            ),
                        ),
                )
            } else if (result.revocation == AuthenticationCheck.REVOKED) {
                result.copy(
                    issues =
                        listOf(
                            AuthenticationIssue(
                                AuthenticationIssueCode.REVOCATION_REVOKED,
                                "A current applicable offline CRL lists a certificate in the signing path.",
                                AUTHENTICITY_FILE,
                            ),
                        ),
                )
            } else {
                result
            }
        }

    private fun evaluateRevocation(
        chain: List<X509Certificate>,
        crls: List<X509CRL>,
        time: java.time.Instant,
    ): AuthenticationCheck {
        if (crls.isEmpty()) return AuthenticationCheck.UNKNOWN
        val at = Date.from(time)
        for ((certificate, issuer) in chain.zipWithNext()) {
            val usable =
                crls.filter { crl ->
                    try {
                        crl.issuerX500Principal == issuer.subjectX500Principal &&
                            !crl.thisUpdate.after(at) &&
                            crl.nextUpdate?.let { !it.before(at) } == true &&
                            issuer.keyUsage?.getOrNull(6) != false &&
                            run {
                                crl.verify(issuer.publicKey)
                                true
                            }
                    } catch (
                        _: Exception,
                    ) {
                        false
                    }
                }
            if (usable.isEmpty()) return AuthenticationCheck.UNKNOWN
            if (usable.any { it.getRevokedCertificate(certificate.serialNumber) != null }) return AuthenticationCheck.REVOKED
        }
        return AuthenticationCheck.GOOD
    }

    private fun invalid(
        code: AuthenticationIssueCode,
        message: String,
        path: String? = null,
        envelope: BundleAuthenticationEnvelope? = null,
    ): BundleAuthenticationResult =
        BundleAuthenticationResult(
            AuthenticationStatus.INVALID,
            envelope?.algorithm,
            envelope?.keyId,
            listOf(AuthenticationIssue(code, message, path)),
        )

    private fun readCoreFile(
        root: Path,
        fileName: String,
        missingCode: VerificationIssueCode,
        issues: MutableList<VerificationIssue>,
    ): String? {
        val path = root.resolve(fileName)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            issues.error(missingCode, "Required bundle file is missing.", fileName)
            return null
        }
        if (Files.isSymbolicLink(path)) {
            issues.error(VerificationIssueCode.SYMBOLIC_LINK, "Bundle files must not be symbolic links.", fileName)
            return null
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            issues.error(VerificationIssueCode.NON_REGULAR_FILE, "Bundle file is not a regular file.", fileName)
            return null
        }
        return try {
            Files.readString(path)
        } catch (error: IOException) {
            issues.error(VerificationIssueCode.NON_REGULAR_FILE, "Bundle file cannot be read: ${error.message}", fileName)
            null
        }
    }

    private fun parseObject(
        text: String,
        fileName: String,
        issues: MutableList<VerificationIssue>,
    ): JsonObject? =
        try {
            evidenceJson.parseToJsonElement(text).jsonObject
        } catch (error: Exception) {
            issues.error(VerificationIssueCode.MALFORMED_JSON, "Malformed JSON: ${error.message}", fileName)
            null
        }

    private fun decodeManifest(
        schemaVersion: Int,
        text: String,
        issues: MutableList<VerificationIssue>,
    ): DecodedManifest? =
        try {
            if (schemaVersion == V3_SCHEMA_VERSION) {
                val manifest = evidenceJson.decodeFromString<EvidenceBundleManifestV3>(text)
                DecodedManifest(
                    manifest.evidenceFiles,
                    listOfNotNull(
                        manifest.artifactBinding.detailPath,
                        manifest.execution.resultPath,
                        manifest.execution.assertionHierarchyPath,
                    ),
                )
            } else {
                val manifest = evidenceJson.decodeFromString<EvidenceBundleManifest>(text)
                DecodedManifest(manifest.evidenceFiles, emptyList())
            }
        } catch (error: Exception) {
            issues.error(VerificationIssueCode.MALFORMED_JSON, "Invalid manifest: ${error.message}", MANIFEST_FILE)
            null
        }

    private fun decodeTimeline(
        parsed: JsonObject,
        text: String,
        issues: MutableList<VerificationIssue>,
    ): TimelineDocument? {
        if (parsed["events"] == null) {
            issues.error(VerificationIssueCode.MALFORMED_JSON, "Timeline must contain events.", TIMELINE_FILE)
            return null
        }
        return try {
            evidenceJson.decodeFromString(text)
        } catch (error: SerializationException) {
            issues.error(VerificationIssueCode.MALFORMED_JSON, "Invalid timeline: ${error.message}", TIMELINE_FILE)
            null
        } catch (error: IllegalArgumentException) {
            issues.error(VerificationIssueCode.MALFORMED_JSON, "Invalid timeline: ${error.message}", TIMELINE_FILE)
            null
        }
    }

    private fun validateRawInventoryPaths(
        inventory: JsonArray,
        issues: MutableList<VerificationIssue>,
    ): List<String> {
        val paths = mutableListOf<String>()
        val mediaTypesByPath = mutableMapOf<String, MutableSet<String>>()
        inventory.forEach { element ->
            val entry = element as? JsonObject
            val path = entry?.get("path")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            if (path == null) {
                issues.error(VerificationIssueCode.MALFORMED_JSON, "Inventory entry has no string path.", MANIFEST_FILE)
                return@forEach
            }
            paths += path
            entry["mediaType"]?.let { value ->
                runCatching { value.jsonPrimitive.content }.getOrNull()?.let { mediaType ->
                    mediaTypesByPath.getOrPut(path) { mutableSetOf() }.add(mediaType)
                }
            }
            if (runCatching { BundleRelativePath(path) }.isFailure) {
                issues.error(VerificationIssueCode.UNSAFE_INVENTORY_PATH, "Inventory path is unsafe.", path)
            }
        }
        paths.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { path ->
            issues.error(VerificationIssueCode.DUPLICATE_INVENTORY_PATH, "Inventory path is duplicated.", path)
        }
        paths.groupBy { it.lowercase(Locale.ROOT) }.filterValues { values -> values.distinct().size > 1 }.values.forEach { values ->
            issues.error(
                VerificationIssueCode.CASE_INSENSITIVE_PATH_COLLISION,
                "Inventory paths collide on case-insensitive filesystems: ${values.distinct()}.",
            )
        }
        mediaTypesByPath.filterValues { it.size > 1 }.keys.forEach { path ->
            issues.error(VerificationIssueCode.MEDIA_TYPE_CONFLICT, "Inventory has conflicting media types.", path)
        }
        return paths
    }

    private fun validateInventoryOrder(
        paths: List<String>,
        issues: MutableList<VerificationIssue>,
    ) {
        if (paths != paths.sorted()) {
            issues.error(
                VerificationIssueCode.INVENTORY_NOT_SORTED,
                "Evidence inventory is not ordered lexicographically by path.",
                MANIFEST_FILE,
            )
        }
    }

    private fun verifyEvidenceFile(
        root: Path,
        relativePath: BundleRelativePath,
        expectedSize: Long,
        expectedSha256: String,
        issues: MutableList<VerificationIssue>,
    ) {
        val path = resolve(root, relativePath)
        val symbolicLink = firstSymbolicLink(root, relativePath)
        if (symbolicLink != null) {
            issues.error(VerificationIssueCode.SYMBOLIC_LINK, "Evidence path contains a symbolic link.", symbolicLink)
            return
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            issues.error(
                VerificationIssueCode.MISSING_REFERENCED_EVIDENCE_FILE,
                "Registered evidence file is missing.",
                relativePath.value,
            )
            return
        }
        if (Files.isSymbolicLink(path)) {
            issues.error(VerificationIssueCode.SYMBOLIC_LINK, "Evidence file must not be a symbolic link.", relativePath.value)
            return
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            issues.error(VerificationIssueCode.NON_REGULAR_FILE, "Evidence path is not a regular file.", relativePath.value)
            return
        }
        val actualSize = files.size(path)
        if (actualSize != expectedSize) {
            issues.error(
                VerificationIssueCode.FILE_SIZE_MISMATCH,
                "Expected $expectedSize bytes but found $actualSize.",
                relativePath.value,
            )
        }
        val actualSha256 = files.hash(path)
        if (actualSha256 != expectedSha256) {
            issues.error(VerificationIssueCode.SHA256_MISMATCH, "Evidence SHA-256 does not match.", relativePath.value)
        }
    }

    private fun scanUnexpectedFiles(
        root: Path,
        registeredPaths: Set<String>,
        issues: MutableList<VerificationIssue>,
    ) {
        ioIssue(issues, null) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@ioIssue
            files.walk(root).use { paths ->
                paths.filter { it != root && !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { path ->
                    val relative = root.relativize(path).joinToString("/")
                    if (Files.isSymbolicLink(path) && relative !in registeredPaths) {
                        issues.error(VerificationIssueCode.SYMBOLIC_LINK, "Unexpected symbolic link in bundle.", relative)
                    } else if (relative !in registeredPaths && relative !in CORE_FILES) {
                        issues.error(VerificationIssueCode.UNEXPECTED_EVIDENCE_FILE, "File is not registered in inventory.", relative)
                    }
                }
            }
        }
    }

    private fun resolve(
        root: Path,
        relativePath: BundleRelativePath,
    ): Path = relativePath.value.split('/').fold(root) { current, segment -> current.resolve(segment) }

    private fun firstSymbolicLink(
        root: Path,
        relativePath: BundleRelativePath,
    ): String? {
        var current = root
        relativePath.value.split('/').forEach { segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return root.relativize(current).joinToString("/")
        }
        return null
    }

    private fun JsonObject.schemaVersion(): Int? = (this["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private inline fun ioIssue(
        issues: MutableList<VerificationIssue>,
        path: String?,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (error: IOException) {
            issues.error(VerificationIssueCode.IO_ERROR, "Bundle I/O failed: ${error.message}", path)
        } catch (error: UncheckedIOException) {
            issues.error(VerificationIssueCode.IO_ERROR, "Bundle traversal failed: ${error.cause?.message}", path)
        } catch (error: SecurityException) {
            issues.error(VerificationIssueCode.IO_ERROR, "Bundle access denied: ${error.message}", path)
        }
    }

    private fun MutableList<VerificationIssue>.error(
        code: VerificationIssueCode,
        message: String,
        path: String? = null,
    ) = add(VerificationIssue(code, VerificationSeverity.ERROR, message, path))

    private fun MutableList<VerificationIssue>.warning(
        code: VerificationIssueCode,
        message: String,
        path: String? = null,
    ) = add(VerificationIssue(code, VerificationSeverity.WARNING, message, path))

    private companion object {
        val CORE_FILES = setOf(MANIFEST_FILE, TIMELINE_FILE, AUTHENTICITY_FILE)
        const val MAX_AUTHENTICATION_FILE_BYTES = 256 * 1024L
        const val ED25519_SIGNATURE_BYTES = 64
    }

    private data class DecodedManifest(
        val evidenceFiles: List<EvidenceFileDescriptor>,
        val references: List<BundleRelativePath>,
    )

    private data class VerificationReference(
        val path: BundleRelativePath,
        val mediaType: String? = null,
    )
}
