package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.evidence.Sha256Calculator
import io.github.fredleonam.droidproof.model.Sha256
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock

data class StagedArtifact(
    val path: Path,
    val sha256: Sha256,
)

data class ArtifactBindingState(
    val stagedArtifact: StagedArtifact,
    val action: InstallationAction,
    val beforeLaunch: InstalledArtifactObservation,
    val afterCapture: InstalledArtifactObservation? = null,
)

data class BindingAttempt(
    val state: ArtifactBindingState? = null,
    val document: ArtifactBindingDocument,
    val error: String? = null,
    val cancelled: Boolean = false,
)

data class FinalBindingCheck(
    val state: ArtifactBindingState,
    val cancelled: Boolean = false,
)

class ArtifactBinder(
    private val device: SmokeDeviceOperations,
    private val wallClock: Clock = Clock.systemUTC(),
) {
    fun snapshot(
        source: Path,
        workDirectory: Path,
    ): StagedArtifact {
        require(source.fileName.toString().endsWith(".apk", ignoreCase = true)) {
            "Only one local APK file is supported; AAB and split inputs are not accepted."
        }
        val attributes = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() > 0) {
            "APK source must be a nonempty regular non-symbolic-link file."
        }
        Files.createDirectories(workDirectory)
        val snapshot = workDirectory.resolve("input.apk")
        Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { input ->
            Files.newOutputStream(snapshot, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
            }
        }
        return StagedArtifact(snapshot, Sha256Calculator.calculate(snapshot))
    }

    fun bind(
        artifact: StagedArtifact,
        serial: String,
        packageName: String,
        replaceExisting: Boolean,
        timeoutMillis: () -> Long,
    ): BindingAttempt {
        val initialPaths = device.packagePaths(serial, packageName, timeoutMillis())
        if (!initialPaths.isSuccessful) return failed(artifact.sha256, packageName, initialPaths, InstallationAction.NOT_ATTEMPTED)
        val paths = requireNotNull(initialPaths.value).paths
        if (paths.size > 1) {
            return failure(artifact.sha256, packageName, InstallationAction.NOT_ATTEMPTED, "Split installations are unsupported.")
        }

        if (paths.isEmpty()) {
            val install = device.installApk(serial, artifact.path, false, timeoutMillis())
            if (!install.isSuccessful) {
                return failed(artifact.sha256, packageName, install, InstallationAction.INSTALLED_ABSENT_PACKAGE)
            }
            return verifyInstalled(artifact, serial, packageName, InstallationAction.INSTALLED_ABSENT_PACKAGE, timeoutMillis)
        }

        val existingAttempt = observeInstalled(serial, paths.single(), timeoutMillis)
        val existing = existingAttempt.observation
        if (existing.sha256 == artifact.sha256) {
            return success(artifact, packageName, InstallationAction.REUSED_MATCHING_INSTALLATION, existing)
        }
        if (existing.sha256 == null) {
            return failure(
                artifact.sha256,
                packageName,
                InstallationAction.NOT_ATTEMPTED,
                existing.unavailableReason ?: "Installed APK could not be read.",
                existing,
                existingAttempt.cancelled,
            )
        }
        if (!replaceExisting) {
            return failure(
                artifact.sha256,
                packageName,
                InstallationAction.REFUSED_DIFFERENT_INSTALLATION,
                "Installed APK bytes differ; set replaceExisting only when changing the test app installation is intended.",
                existing,
            )
        }
        val install = device.installApk(serial, artifact.path, true, timeoutMillis())
        if (!install.isSuccessful) return failed(artifact.sha256, packageName, install, InstallationAction.REPLACED_EXISTING_INSTALLATION)
        return verifyInstalled(artifact, serial, packageName, InstallationAction.REPLACED_EXISTING_INSTALLATION, timeoutMillis)
    }

    fun finalCheck(
        state: ArtifactBindingState,
        serial: String,
        packageName: String,
        timeoutMillis: () -> Long,
    ): FinalBindingCheck {
        val paths = device.packagePaths(serial, packageName, timeoutMillis())
        var cancelled = paths.failure == DeviceFailureKind.CANCELLED
        val observation =
            when {
                !paths.isSuccessful ->
                    InstalledArtifactObservation(
                        wallClock.instant().toString(),
                        emptyList(),
                        unavailableReason = paths.detail ?: "Final package inspection failed.",
                    )
                requireNotNull(paths.value).paths.size != 1 ->
                    InstalledArtifactObservation(
                        wallClock.instant().toString(),
                        requireNotNull(paths.value).paths,
                        unavailableReason = "Final package inspection did not return exactly one APK path.",
                    )
                else -> {
                    val attempt = observeInstalled(serial, requireNotNull(paths.value).paths.single(), timeoutMillis)
                    cancelled = attempt.cancelled
                    attempt.observation
                }
            }
        return FinalBindingCheck(state.copy(afterCapture = observation), cancelled)
    }

    private fun verifyInstalled(
        artifact: StagedArtifact,
        serial: String,
        packageName: String,
        action: InstallationAction,
        timeoutMillis: () -> Long,
    ): BindingAttempt {
        val paths = device.packagePaths(serial, packageName, timeoutMillis())
        if (!paths.isSuccessful) return failed(artifact.sha256, packageName, paths, action)
        val values = requireNotNull(paths.value).paths
        if (values.size != 1) {
            return failure(
                artifact.sha256,
                packageName,
                action,
                "Expected package did not resolve to exactly one installed APK after installation.",
            )
        }
        val observationAttempt = observeInstalled(serial, values.single(), timeoutMillis)
        val observation = observationAttempt.observation
        if (observation.sha256 != artifact.sha256) {
            return failure(
                artifact.sha256,
                packageName,
                action,
                observation.unavailableReason ?: "Installed APK bytes do not match the staged input APK.",
                observation,
                observationAttempt.cancelled,
            )
        }
        return success(artifact, packageName, action, observation)
    }

    private fun observeInstalled(
        serial: String,
        remotePath: String,
        timeoutMillis: () -> Long,
    ): InstalledObservationAttempt {
        val temporary = Files.createTempFile("droidproof-installed-", ".apk")
        return try {
            val pull = device.pullApk(serial, remotePath, temporary, timeoutMillis())
            if (!pull.isSuccessful) {
                InstalledObservationAttempt(
                    InstalledArtifactObservation(
                        wallClock.instant().toString(),
                        listOf(remotePath),
                        unavailableReason = pull.detail ?: "Installed APK retrieval failed.",
                    ),
                    pull.failure == DeviceFailureKind.CANCELLED,
                )
            } else {
                InstalledObservationAttempt(
                    InstalledArtifactObservation(
                        wallClock.instant().toString(),
                        listOf(remotePath),
                        sha256 = Sha256Calculator.calculate(temporary),
                    ),
                )
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun success(
        artifact: StagedArtifact,
        packageName: String,
        action: InstallationAction,
        observation: InstalledArtifactObservation,
    ): BindingAttempt {
        val state = ArtifactBindingState(artifact, action, observation)
        return BindingAttempt(
            state,
            ArtifactBindingDocument(
                packageName = packageName,
                inputApkSha256 = artifact.sha256,
                action = action,
                beforeLaunch = observation,
            ),
        )
    }

    private fun <T> failed(
        digest: Sha256,
        packageName: String,
        call: DeviceCall<T>,
        action: InstallationAction,
    ): BindingAttempt =
        failure(
            digest,
            packageName,
            action,
            call.detail ?: "Artifact-binding device operation failed.",
            cancelled = call.failure == DeviceFailureKind.CANCELLED,
        )

    private fun failure(
        digest: Sha256,
        packageName: String,
        action: InstallationAction,
        error: String,
        observation: InstalledArtifactObservation? = null,
        cancelled: Boolean = false,
    ): BindingAttempt =
        BindingAttempt(
            document =
                ArtifactBindingDocument(
                    packageName = packageName,
                    inputApkSha256 = digest,
                    action = action,
                    beforeLaunch = observation,
                ),
            error = error,
            cancelled = cancelled,
        )

    private companion object {
        const val COPY_BUFFER_SIZE = 8 * 1024
    }

    private data class InstalledObservationAttempt(
        val observation: InstalledArtifactObservation,
        val cancelled: Boolean = false,
    )
}
