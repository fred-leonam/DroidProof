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
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

const val AUTHENTICITY_FILE = "authenticity.json"
const val AUTHENTICATION_SCHEMA_VERSION = 1
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
)

data class BundleSigningConfiguration(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
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
    private const val DOMAIN_SEPARATOR = "DroidProof authenticated evidence bundle v1\n"
}
