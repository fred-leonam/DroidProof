package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CommandRequest
import io.github.fredleonam.droidproof.device.CommandResult
import io.github.fredleonam.droidproof.device.CommandRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmulatorProvisioningTest {
    private val contractJson =
        """{"schemaVersion":1,"systemImagePackage":"system-images;android-35;google_apis;x86_64",""" +
            """"systemImageRevision":"1","apiLevel":35,"abi":"x86_64","emulatorRevision":"35.1.4",""" +
            """"platformToolsRevision":"35.0.2","commandLineToolsRevision":"12.0","deviceProfile":"pixel_5"}"""

    @Test fun `loader preserves exact accepted bytes and rejects schema hazards`() =
        temp { root ->
            val file = root.resolve("contract.json")
            val bytes = contractJson.toByteArray()
            file.writeBytes(bytes)
            val accepted = EmulatorProvisioningContractLoader.load(file)
            assertContentEquals(bytes, accepted.exactBytes)
            assertEquals("1", accepted.contract.systemImageRevision)
            assertFailsWith<Throwable> {
                root.resolve("empty").writeText("")
                EmulatorProvisioningContractLoader.load(root.resolve("empty"))
            }
            root.resolve("bad").writeBytes(byteArrayOf(0x80.toByte()))
            assertFailsWith<Throwable> { EmulatorProvisioningContractLoader.load(root.resolve("bad")) }
            root.resolve("unknown").writeText(contractJson.dropLast(1) + ",\"extra\":true}")
            assertFailsWith<Throwable> { EmulatorProvisioningContractLoader.load(root.resolve("unknown")) }
            root.resolve("v2").writeText(contractJson.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
            assertFailsWith<Throwable> { EmulatorProvisioningContractLoader.load(root.resolve("v2")) }
            assertFailsWith<Throwable> { EmulatorProvisioningContractLoader.load(root) }
        }

    @Test fun `provisions only owned paths and propagates isolated avd home`() =
        temp { root ->
            val accepted = accept(root)
            val sdk = sdk(root)
            val state = root.resolve("state")
            val requests = mutableListOf<CommandRequest>()
            var closed = 0
            val runner =
                CommandRunner { request ->
                    requests += request
                    if (request.arguments.drop(1).take(2) == listOf("create", "avd")) {
                        val path = Path.of(request.arguments[request.arguments.indexOf("--path") + 1])
                        path.resolve(
                            "config.ini",
                        ).writeText(
                            "image.sysdir.1=system-images/android-35/google_apis/x86_64\n" +
                                "abi.type=x86_64\n",
                        )
                    }
                    when (request.arguments.last()) {
                        "ro.build.version.sdk" -> CommandResult("35", exitCode = 0)
                        "ro.product.cpu.abi" -> CommandResult("x86_64", exitCode = 0)
                        else -> CommandResult(exitCode = 0)
                    }
                }
            val lifecycle =
                object : EmulatorLifecycleManager {
                    override fun start(configuration: EmulatorLifecycleConfiguration): ManagedEmulatorSession {
                        assertEquals(
                            mapOf(
                                "ANDROID_AVD_HOME" to configuration.ownedAvdDirectory!!.resolve("avd-home").toString(),
                            ),
                            configuration.environment,
                        )
                        assertEquals(1_234L, configuration.startupTimeoutMillis)
                        assertEquals(5_678L, configuration.shutdownTimeoutMillis)
                        return object : ManagedEmulatorSession {
                            override val serial = "emulator-5554"

                            override fun close() {
                                closed++
                            }
                        }
                    }
                }
            val session =
                LegacySdkEmulatorProvisioner(runner, lifecycle).provision(
                    config(accepted, sdk, state, startupTimeoutMillis = 1_234, shutdownTimeoutMillis = 5_678),
                )
            val create = requests.single { it.arguments.contains("create") }
            assertEquals(
                listOf(
                    "avdmanager", "create", "avd", "--name",
                    "droidproof-${accepted.sha256.value.take(
                        24,
                    )}",
                    "--package",
                    accepted.contract.systemImagePackage,
                    "--device",
                    "pixel_5",
                    "--path",
                    session.avdDirectory.toString(),
                    "--force",
                ),
                create.arguments,
            )
            assertEquals(
                session.avdDirectory.resolve("avd-home").toString(),
                create.environment["ANDROID_AVD_HOME"],
            )
            session.close()
            session.close()
            assertEquals(1, closed)
            assertFalse(Files.exists(session.avdDirectory))
        }

    @Test fun `refuses preexisting directory and preserves marker mismatch`() =
        temp { root ->
            val accepted = accept(root)
            val state = root.resolve("state")
            Files.createDirectories(state)
            val key = accepted.sha256.value.take(24)
            Files.createDirectories(state.resolve(key))
            assertFailsWith<EmulatorProvisioningException> {
                LegacySdkEmulatorProvisioner().provision(config(accepted, sdk(root), state))
            }
            assertTrue(Files.exists(state.resolve(key)))
        }

    private fun accept(root: Path) =
        root.resolve("contract.json").also {
            it.writeText(contractJson)
        }.let(EmulatorProvisioningContractLoader::load)

    private fun config(
        accepted: AcceptedProvisioningContract,
        sdk: Path,
        state: Path,
        startupTimeoutMillis: Long = 1_000,
        shutdownTimeoutMillis: Long = 30_000,
    ) = EmulatorProvisioningConfiguration(
        accepted = accepted,
        sdkRoot = sdk,
        stateRoot = state,
        avdManagerPath = Path.of("avdmanager"),
        emulatorPath = Path.of("emulator"),
        adbPath = Path.of("adb"),
        port = 5554,
        timeoutMillis = startupTimeoutMillis,
        shutdownTimeoutMillis = shutdownTimeoutMillis,
    )

    private fun sdk(root: Path): Path {
        val sdk = root.resolve("sdk")

        fun metadata(
            path: String,
            revision: String,
        ) {
            sdk.resolve(path).also {
                Files.createDirectories(it.parent)
                it.writeText("Pkg.Revision=$revision\n")
            }
        }
        metadata("emulator/source.properties", "35.1.4")
        metadata("platform-tools/source.properties", "35.0.2")
        metadata("cmdline-tools/12.0/source.properties", "12.0")
        metadata("system-images/android-35/google_apis/x86_64/source.properties", "1")
        return sdk.toAbsolutePath()
    }

    private fun temp(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("provision-test")
        try {
            block(root)
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
