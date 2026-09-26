package io.github.fredleonam.droidproof.gradle

import io.github.fredleonam.droidproof.evidence.AuthenticationStatus
import io.github.fredleonam.droidproof.evidence.Ed25519KeyLoader
import io.github.fredleonam.droidproof.host.EmulatorBackend
import io.github.fredleonam.droidproof.host.RunSmokeScenarioConfiguration
import io.github.fredleonam.droidproof.host.SmokeScenarioLoader
import io.github.fredleonam.droidproof.host.runSmokeScenario
import io.github.fredleonam.droidproof.model.EnvironmentExecutionMode
import io.github.fredleonam.droidproof.report.EvidenceReportGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Path

abstract class DroidProofExtension {
    abstract val apk: RegularFileProperty
    abstract val scenario: RegularFileProperty
    abstract val outputRoot: DirectoryProperty
    abstract val deviceSerial: Property<String>
    abstract val adbPath: RegularFileProperty
    abstract val replaceExisting: Property<Boolean>
    abstract val signingPrivateKey: RegularFileProperty
    abstract val signingPublicKey: RegularFileProperty
    abstract val environment: RegularFileProperty
    abstract val environmentMode: Property<EnvironmentExecutionMode>
    abstract val recoveryStateRoot: DirectoryProperty
    abstract val avdName: Property<String>
    abstract val emulatorPath: Property<String>
    abstract val emulatorPort: Property<Int>
    abstract val lifecycleStartupTimeoutMillis: Property<Long>
    abstract val lifecycleShutdownTimeoutMillis: Property<Long>
    abstract val provisioning: RegularFileProperty
    abstract val sdkRoot: DirectoryProperty
    abstract val provisioningStateRoot: DirectoryProperty
    abstract val avdManagerPath: Property<String>
    abstract val emulatorBackend: Property<EmulatorBackend>
    abstract val androidCliPath: RegularFileProperty
    abstract val bundle: DirectoryProperty
    abstract val report: RegularFileProperty
    abstract val trustedPublicKey: RegularFileProperty
}

class DroidProofPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("droidProof", DroidProofExtension::class.java)
        extension.outputRoot.convention(project.layout.buildDirectory.dir("droidproof-runs"))
        extension.replaceExisting.convention(false)
        extension.environmentMode.convention(EnvironmentExecutionMode.VERIFY_ONLY)
        extension.recoveryStateRoot.convention(project.layout.projectDirectory.dir(".droidproof-recovery"))
        extension.emulatorPath.convention("emulator")
        extension.emulatorPort.convention(5554)
        extension.lifecycleStartupTimeoutMillis.convention(120_000)
        extension.lifecycleShutdownTimeoutMillis.convention(30_000)
        extension.provisioningStateRoot.convention(project.layout.projectDirectory.dir(".droidproof-provisioning"))
        extension.avdManagerPath.convention("avdmanager")
        extension.emulatorBackend.convention(EmulatorBackend.LEGACY)
        extension.report.convention(project.layout.buildDirectory.file("reports/droidproof/evidence-report.html"))

        project.tasks.register("droidProofRun", DroidProofRunTask::class.java) {
            it.group = "droidproof"
            it.description = "Runs one artifact-bound Android scenario and publishes its evidence."
            it.configuration = extension
            it.droidProofVersion = pluginVersion()
        }
        project.tasks.register("droidProofValidateScenario", DroidProofValidateScenarioTask::class.java) {
            it.group = "verification"
            it.description = "Validates the configured DroidProof scenario."
            it.configuration = extension
        }
        project.tasks.register("droidProofReport", DroidProofReportTask::class.java) {
            it.group = "droidproof"
            it.description = "Verifies a DroidProof evidence bundle and generates offline HTML."
            it.configuration = extension
        }
    }

    private fun pluginVersion(): String = javaClass.`package`.implementationVersion ?: "0.1.0-SNAPSHOT"
}

@DisableCachingByDefault(because = "Runs mutable external Android target operations")
abstract class DroidProofRunTask : DefaultTask() {
    @get:Internal lateinit var configuration: DroidProofExtension

    @get:Internal lateinit var droidProofVersion: String

    @TaskAction
    fun run() {
        val extension = configuration
        runSmokeScenario(
            RunSmokeScenarioConfiguration(
                extension.outputRoot.get().asFile.toPath(),
                extension.apk.get().asFile.toPath(),
                extension.scenario.get().asFile.toPath(),
                extension.deviceSerial.orNull,
                extension.adbPath.orNull?.asFile?.absolutePath,
                extension.replaceExisting.get(),
                extension.signingPrivateKey.orNull?.asFile?.toPath(),
                extension.signingPublicKey.orNull?.asFile?.toPath(),
                extension.environment.orNull?.asFile?.toPath(),
                extension.environmentMode.get(),
                extension.recoveryStateRoot.get().asFile.toPath(),
                extension.avdName.orNull,
                Path.of(extension.emulatorPath.get()),
                extension.emulatorPort.get(),
                extension.lifecycleStartupTimeoutMillis.get(),
                extension.lifecycleShutdownTimeoutMillis.get(),
                extension.provisioning.orNull?.asFile?.toPath(),
                extension.sdkRoot.orNull?.asFile?.toPath(),
                extension.provisioningStateRoot.get().asFile.toPath(),
                Path.of(extension.avdManagerPath.get()),
                droidProofVersion,
                extension.emulatorBackend.get(),
                extension.androidCliPath.orNull?.asFile?.toPath(),
            ),
        )
    }
}

@DisableCachingByDefault(because = "Validation is intentionally explicit")
abstract class DroidProofValidateScenarioTask : DefaultTask() {
    @get:Internal lateinit var configuration: DroidProofExtension

    @TaskAction
    fun validate() {
        val accepted = SmokeScenarioLoader.load(configuration.scenario.get().asFile.toPath())
        logger.lifecycle(
            "Valid DroidProof scenario v{}: {} ({})",
            accepted.scenario.schemaVersion,
            accepted.scenario.scenarioId.value,
            accepted.sha256.value,
        )
    }
}

@DisableCachingByDefault(because = "Always verifies the selected bundle before rendering")
abstract class DroidProofReportTask : DefaultTask() {
    @get:Internal lateinit var configuration: DroidProofExtension

    @TaskAction
    fun generate() {
        val extension = configuration
        val trustedKey =
            extension.trustedPublicKey.orNull?.asFile?.toPath()?.let(Ed25519KeyLoader::loadPublicKey)
        val result =
            EvidenceReportGenerator().generate(
                extension.bundle.get().asFile.toPath(),
                extension.report.get().asFile.toPath(),
                trustedKey,
            )
        logger.lifecycle("DroidProof evidence report: {}", result.output)
        check(result.verification.isValid) { "Bundle verification failed; a diagnostic report was written." }
        check(result.verification.authentication.status != AuthenticationStatus.INVALID) {
            "Bundle authentication failed; a diagnostic report was written."
        }
    }
}
