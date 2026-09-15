package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.evidence.Sha256Calculator
import io.github.fredleonam.droidproof.model.EmulatorEnvironmentContractV1
import io.github.fredleonam.droidproof.model.Sha256
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

data class AcceptedEnvironmentContract(
    val contract: EmulatorEnvironmentContractV1,
    val exactBytes: ByteArray,
    val sha256: Sha256,
)

object EnvironmentContractLoader {
    fun load(path: Path): AcceptedEnvironmentContract {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink) {
            "Environment contract must be a regular non-symbolic-link file."
        }
        require(attributes.size() in 1..MAX_ENVIRONMENT_CONTRACT_BYTES) {
            "Environment contract must contain 1 to $MAX_ENVIRONMENT_CONTRACT_BYTES bytes."
        }
        val bytes =
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use {
                it.readNBytes(MAX_ENVIRONMENT_CONTRACT_BYTES.toInt() + 1)
            }
        require(bytes.size.toLong() in 1..MAX_ENVIRONMENT_CONTRACT_BYTES) {
            "Environment contract exceeds accepted byte bounds."
        }
        val text =
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        val version = environmentJson.parseToJsonElement(text).jsonObject.getValue("schemaVersion").jsonPrimitive.int
        require(version == 1) { "Unsupported environment-contract schema version: $version." }
        val contract = environmentJson.decodeFromString<EmulatorEnvironmentContractV1>(text)
        return AcceptedEnvironmentContract(contract, bytes, Sha256Calculator.calculate(ByteArrayInputStream(bytes)))
    }

    private const val MAX_ENVIRONMENT_CONTRACT_BYTES = 64L * 1024L
}

private val environmentJson =
    Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }
