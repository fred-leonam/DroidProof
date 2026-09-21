package io.github.fredleonam.droidproof.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RunSmokeScenarioConfigurationTest {
    @Test fun `named wiring keeps provisioning path and project version distinct`() {
        val configuration =
            RunSmokeScenarioConfiguration.parse(
                arguments("provisioningPath=/contracts/device.json", "sdkRoot=/sdk", "version=9.8.7"),
            )
        assertEquals("/contracts/device.json", configuration.provisioningPath.toString())
        assertEquals("/sdk", configuration.sdkRoot.toString())
        assertEquals("9.8.7", configuration.version)
    }

    @Test fun `external serial does not require provisioning executables`() {
        assertEquals("emulator-5554", RunSmokeScenarioConfiguration.parse(arguments("deviceSerial=emulator-5554")).deviceSerial)
    }

    @Test fun `existing avd and legacy provisioning parse explicitly`() {
        assertEquals("Existing_API_35", RunSmokeScenarioConfiguration.parse(arguments("avdName=Existing_API_35")).avdName)
        assertEquals(
            EmulatorBackend.LEGACY,
            RunSmokeScenarioConfiguration.parse(arguments("provisioningPath=/p", "sdkRoot=/sdk")).emulatorBackend,
        )
    }

    @Test fun `android cli provisioning requires explicit executable`() {
        assertFailsWith<IllegalArgumentException> {
            RunSmokeScenarioConfiguration.parse(arguments("provisioningPath=/p", "sdkRoot=/sdk", "emulatorBackend=android-cli"))
        }
        assertEquals(
            EmulatorBackend.ANDROID_CLI,
            RunSmokeScenarioConfiguration.parse(
                arguments("provisioningPath=/p", "sdkRoot=/sdk", "emulatorBackend=android-cli", "androidCliPath=/tools/android"),
            ).emulatorBackend,
        )
    }

    @Test fun `missing invalid and conflicting values fail centrally`() {
        assertFailsWith<IllegalArgumentException> { RunSmokeScenarioConfiguration.parse(arguments("deviceSerial=")) }
        assertFailsWith<IllegalArgumentException> { RunSmokeScenarioConfiguration.parse(arguments("deviceSerial=x", "avdName=y")) }
        assertFailsWith<IllegalArgumentException> { RunSmokeScenarioConfiguration.parse(arguments("deviceSerial=x", "emulatorPort=5555")) }
        assertFailsWith<IllegalArgumentException> {
            RunSmokeScenarioConfiguration.parse(
                arguments("deviceSerial=x", "emulatorBackend=unknown"),
            )
        }
    }

    @Test fun `configured lifecycle timeouts parse independently`() {
        val configuration =
            RunSmokeScenarioConfiguration.parse(
                arguments(
                    "provisioningPath=/p",
                    "sdkRoot=/sdk",
                    "lifecycleStartupTimeoutMillis=1234",
                    "lifecycleShutdownTimeoutMillis=5678",
                ),
            )
        assertEquals(1234L, configuration.lifecycleStartupTimeoutMillis)
        assertEquals(5678L, configuration.lifecycleShutdownTimeoutMillis)
    }

    private fun arguments(vararg overrides: String): Array<String> {
        val values =
            linkedMapOf(
                "outputRoot" to "/out", "apkPath" to "/app.apk", "scenarioPath" to "/scenario.json",
                "deviceSerial" to "", "adbPath" to "", "replaceExisting" to "false",
                "signingPrivateKeyPath" to "", "signingPublicKeyPath" to "", "environmentPath" to "",
                "environmentMode" to "VERIFY_ONLY", "recoveryStateRoot" to "/recovery", "avdName" to "",
                "emulatorPath" to "emulator", "emulatorPort" to "5554", "lifecycleStartupTimeoutMillis" to "120000",
                "lifecycleShutdownTimeoutMillis" to "30000", "provisioningPath" to "", "sdkRoot" to "",
                "provisioningStateRoot" to "/state", "avdManagerPath" to "avdmanager", "version" to "1.2.3",
                "emulatorBackend" to "legacy", "androidCliPath" to "",
            )
        overrides.forEach {
            val (key, value) = it.split('=', limit = 2)
            values[key] = value
        }
        return values.map { "--${it.key}=${it.value}" }.toTypedArray()
    }
}
