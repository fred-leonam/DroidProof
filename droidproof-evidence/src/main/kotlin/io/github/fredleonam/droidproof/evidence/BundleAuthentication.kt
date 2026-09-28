package io.github.fredleonam.droidproof.evidence

import io.github.fredleonam.droidproof.model.Sha256
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

const val AUTHENTICITY_FILE = "authenticity.json"
const val AUTHENTICATION_SCHEMA_VERSION = 1
const val CERTIFICATE_AUTHENTICATION_SCHEMA_VERSION = 2
const val AUTHENTICATION_ALGORITHM = "Ed25519"

@Serializable
data class AuthenticatedCoreFile(
    val path: String,
    val sha256: Sha256,
    val byteSize: Long,
)

@Serializable
data class BundleAuthenticationEnvelope(
    val schemaVersion: Int,
    val algorithm: String,
    val keyId: Sha256,
    val coreFiles: List<AuthenticatedCoreFile>,
    val signature: String,
    /** DER certificates, base64 encoded, leaf first. Present only in schema v2. */
    val certificateChain: List<String>? = null,
)

data class BundleSigningConfiguration(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
    val certificateChain: List<X509Certificate> = emptyList(),
)

enum class AuthenticationCheck { NOT_CHECKED, GOOD, FAILED, UNKNOWN, REVOKED }

/** Inputs are deliberately external to the bundle. No network retrieval is performed. */
data class CertificateVerificationConfiguration(
    val trustAnchors: Set<X509Certificate> = emptySet(),
    val crls: List<X509CRL> = emptyList(),
    val evaluationTime: java.time.Instant = java.time.Instant.now(),
    val requireCertificateTrust: Boolean = false,
    val requireGoodRevocation: Boolean = false,
) {
    /** Good offline revocation evidence is meaningful only for a trusted version 2 certificate path. */
    val requiresCertificatePath: Boolean get() = requireCertificateTrust || requireGoodRevocation
}

data class BundleVerificationConfiguration(
    val trustedPublicKey: PublicKey? = null,
    val certificate: CertificateVerificationConfiguration? = null,
)

enum class AuthenticationStatus {
    UNSIGNED,
    SIGNED_UNTRUSTED,
    AUTHENTICATED,
    INVALID,
}

enum class AuthenticationIssueCode {
    AUTHENTICATION_REQUIRED,
    AUTHENTICATION_SYMBOLIC_LINK,
    AUTHENTICATION_NON_REGULAR_FILE,
    AUTHENTICATION_FILE_TOO_LARGE,
    AUTHENTICATION_IO_ERROR,
    MALFORMED_AUTHENTICATION_JSON,
    UNSUPPORTED_AUTHENTICATION_SCHEMA,
    UNSUPPORTED_AUTHENTICATION_ALGORITHM,
    INVALID_AUTHENTICATION_CORE_FILES,
    AUTHENTICATED_CORE_SIZE_MISMATCH,
    AUTHENTICATED_CORE_SHA256_MISMATCH,
    TRUSTED_KEY_ID_MISMATCH,
    MALFORMED_SIGNATURE,
    INVALID_SIGNATURE,
    BUNDLE_INTEGRITY_FAILED,
    CERTIFICATE_CHAIN_MISSING,
    CERTIFICATE_CHAIN_MALFORMED,
    CERTIFICATE_CHAIN_DUPLICATE,
    CERTIFICATE_CHAIN_ORDER_INVALID,
    CERTIFICATE_LEAF_KEY_MISMATCH,
    CERTIFICATE_TRUST_NOT_CONFIGURED,
    CERTIFICATE_CHAIN_UNTRUSTED,
    CERTIFICATE_POLICY_FAILED,
    REVOCATION_UNKNOWN,
    REVOCATION_REVOKED,
    STRICT_CERTIFICATE_TRUST_REQUIRED,
    STRICT_GOOD_REVOCATION_REQUIRED,
}

data class AuthenticationIssue(
    val code: AuthenticationIssueCode,
    val message: String,
    val path: String? = null,
)

data class BundleAuthenticationResult(
    val status: AuthenticationStatus,
    val algorithm: String? = null,
    val keyId: Sha256? = null,
    val issues: List<AuthenticationIssue> = emptyList(),
    val signature: AuthenticationCheck = AuthenticationCheck.NOT_CHECKED,
    val chainTrust: AuthenticationCheck = AuthenticationCheck.NOT_CHECKED,
    val revocation: AuthenticationCheck = AuthenticationCheck.NOT_CHECKED,
    val evaluationTime: java.time.Instant? = null,
) {
    val isAuthenticated: Boolean get() = status == AuthenticationStatus.AUTHENTICATED
}

object Ed25519KeyLoader {
    fun loadPrivateKey(path: Path): PrivateKey =
        KeyFactory.getInstance(AUTHENTICATION_ALGORITHM).generatePrivate(
            PKCS8EncodedKeySpec(readKey(path, "PRIVATE KEY")),
        )

    fun loadPublicKey(path: Path): PublicKey =
        KeyFactory.getInstance(AUTHENTICATION_ALGORITHM).generatePublic(
            X509EncodedKeySpec(readKey(path, "PUBLIC KEY")),
        )

    private fun readKey(
        path: Path,
        pemLabel: String,
    ): ByteArray {
        if (Files.isSymbolicLink(path)) throw IllegalArgumentException("Key file must not be a symbolic link.")
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalArgumentException("Key file must be a regular file.")
        }
        val encoded = readBounded(path)
        val text = runCatching { StandardCharsets.US_ASCII.newDecoder().decode(java.nio.ByteBuffer.wrap(encoded)).toString() }.getOrNull()
        if (text == null || !text.startsWith("-----BEGIN ")) return encoded
        val begin = "-----BEGIN $pemLabel-----"
        val end = "-----END $pemLabel-----"
        require(text.startsWith(begin) && text.trimEnd().endsWith(end)) { "Key PEM label must be $pemLabel." }
        val body = text.removePrefix(begin).trimStart().substringBeforeLast(end).filterNot(Char::isWhitespace)
        require(body.isNotEmpty() && body.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) {
            "Key PEM body is malformed."
        }
        return try {
            Base64.getDecoder().decode(body)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Key PEM body is not valid Base64.", error)
        }
    }

    private fun readBounded(path: Path): ByteArray {
        if (Files.size(path) > MAX_KEY_FILE_BYTES) throw IllegalArgumentException("Key file exceeds $MAX_KEY_FILE_BYTES bytes.")
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_KEY_FILE_BYTES) throw IllegalArgumentException("Key file exceeds $MAX_KEY_FILE_BYTES bytes.")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private const val MAX_KEY_FILE_BYTES = 64 * 1024
}

/** Strict bounded loaders for verifier supplied certificate and CRL files. */
object OfflineCertificateEvidenceLoader {
    private const val MAX_BYTES = 1024 * 1024

    fun certificates(path: Path): List<X509Certificate> =
        load(path).let { bytes ->
            val values =
                CertificateFactory.getInstance("X.509").generateCertificates(bytes.inputStream())
                    .map { it as? X509Certificate ?: throw IllegalArgumentException("Certificate input contains a non-X.509 certificate.") }
            require(values.isNotEmpty() && values.size <= 16) { "Certificate input must contain 1 through 16 X.509 certificates." }
            values
        }

    fun crls(path: Path): List<X509CRL> =
        load(path).let { bytes ->
            val values =
                CertificateFactory.getInstance("X.509").generateCRLs(bytes.inputStream())
                    .map { it as? X509CRL ?: throw IllegalArgumentException("Revocation input contains a non-X.509 CRL.") }
            require(values.isNotEmpty() && values.size <= 32) { "Revocation input must contain 1 through 32 X.509 CRLs." }
            values
        }

    private fun load(path: Path): ByteArray {
        require(!Files.isSymbolicLink(path)) { "Certificate input must not be a symbolic link." }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Certificate input must be a regular file." }
        require(Files.size(path) <= MAX_BYTES) { "Certificate input exceeds $MAX_BYTES bytes." }
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            input.readNBytes(MAX_BYTES + 1).also { require(it.size <= MAX_BYTES) { "Certificate input exceeds $MAX_BYTES bytes." } }
        }
    }
}

internal object BundleAuthenticator {
    fun keyId(publicKey: PublicKey): Sha256 = Sha256Calculator.calculate(publicKey.encoded.inputStream())

    fun describe(directory: Path): List<AuthenticatedCoreFile> =
        AUTHENTICATED_CORE_PATHS.map { name ->
            val path = directory.resolve(name)
            AuthenticatedCoreFile(name, Sha256Calculator.calculate(path), Files.size(path))
        }

    fun signingMessage(coreFiles: List<AuthenticatedCoreFile>): ByteArray {
        require(coreFiles.map { it.path } == AUTHENTICATED_CORE_PATHS) { "Authenticated core files are not canonical." }
        val message =
            buildString {
                append(DOMAIN_SEPARATOR)
                coreFiles.forEach { file ->
                    append(file.path).append('\n')
                    append(file.byteSize).append('\n')
                    append(file.sha256.value).append('\n')
                }
            }
        return message.toByteArray(StandardCharsets.US_ASCII)
    }

    fun certificateSigningMessage(
        coreFiles: List<AuthenticatedCoreFile>,
        chain: List<X509Certificate>,
    ): ByteArray {
        require(
            chain.isNotEmpty() && chain.size <= MAX_CERTIFICATES,
        ) { "Certificate chain must contain 1 through $MAX_CERTIFICATES certificates." }
        val chainDescription =
            chain.joinToString("") { certificate ->
                val encoded = certificate.encoded
                "${encoded.size}\n${Sha256Calculator.calculate(encoded.inputStream()).value}\n"
            }
        return (CERTIFICATE_DOMAIN_SEPARATOR + signingMessage(coreFiles).toString(StandardCharsets.US_ASCII) + chainDescription)
            .toByteArray(StandardCharsets.US_ASCII)
    }

    fun sign(
        privateKey: PrivateKey,
        message: ByteArray,
    ): ByteArray =
        Signature.getInstance(AUTHENTICATION_ALGORITHM).run {
            initSign(privateKey)
            update(message)
            sign()
        }

    fun verify(
        publicKey: PublicKey,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean =
        Signature.getInstance(AUTHENTICATION_ALGORITHM).run {
            initVerify(publicKey)
            update(message)
            verify(signature)
        }

    val AUTHENTICATED_CORE_PATHS = listOf(MANIFEST_FILE, TIMELINE_FILE)
    const val MAX_CERTIFICATES = 8
    private const val DOMAIN_SEPARATOR = "DroidProof authenticated evidence bundle v1\n"
    private const val CERTIFICATE_DOMAIN_SEPARATOR = "DroidProof certificate authenticated evidence bundle v2\n"
}
