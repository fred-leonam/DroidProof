import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
}

allprojects {
    group = "io.github.fredleonam.droidproof"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    plugins.withId("maven-publish") {
        providers.gradleProperty("droidproof.publishRepository").orNull?.let { repositoryPath ->
            extensions.configure<PublishingExtension> {
                repositories {
                    maven {
                        name = "isolated"
                        url = uri(repositoryPath)
                    }
                }
            }
        }
    }
}

configure(
    listOf(
        project(":droidproof-model"),
        project(":droidproof-evidence"),
        project(":droidproof-device"),
        project(":droidproof-mock-server"),
        project(":droidproof-host"),
        project(":droidproof-report"),
    ),
) {
    apply(plugin = "maven-publish")
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
    }
}

/**
 * Copies the resolved runtime closure into the repository selected by
 * -Pdroidproof.publishRepository.  The external-consumer check then runs
 * Gradle offline, proving that neither Maven Local nor a network repository
 * is needed to resolve the plugin and scenario DSL.
 */
tasks.register("publishRuntimeDependenciesToIsolatedRepository") {
    val repositoryPath = providers.gradleProperty("droidproof.publishRepository")
    onlyIf { repositoryPath.isPresent }

    doLast {
        val repository = file(repositoryPath.get()).toPath()
        listOf(
            project(":droidproof-cli"),
            project(":droidproof-gradle-plugin"),
            project(":droidproof-scenario-dsl"),
        ).flatMap { runtimeProject ->
            runtimeProject.configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
        }.forEach { artifact ->
            val module = artifact.moduleVersion.id
            val moduleDirectory =
                repository
                    .resolve(module.group.replace('.', '/'))
                    .resolve(module.name)
                    .resolve(module.version)
            Files.createDirectories(moduleDirectory)
            val artifactName = buildString {
                append(module.name)
                append('-')
                append(module.version)
                artifact.classifier?.let {
                    append('-')
                    append(it)
                }
                append('.')
                append(artifact.extension)
            }
            Files.copy(artifact.file.toPath(), moduleDirectory.resolve(artifactName), StandardCopyOption.REPLACE_EXISTING)
            val pom = moduleDirectory.resolve("${module.name}-${module.version}.pom")
            if (Files.notExists(pom)) {
                Files.writeString(
                    pom,
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>${module.group}</groupId>
                      <artifactId>${module.name}</artifactId>
                      <version>${module.version}</version>
                    </project>
                    """.trimIndent(),
                )
            }
        }
        val serializationAlias =
            repository
                .resolve("org/jetbrains/kotlinx/kotlinx-serialization-json/1.6.3")
        Files.createDirectories(serializationAlias)
        Files.writeString(
            serializationAlias.resolve("kotlinx-serialization-json-1.6.3.pom"),
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.jetbrains.kotlinx</groupId>
              <artifactId>kotlinx-serialization-json</artifactId>
              <version>1.6.3</version>
              <dependencies>
                <dependency>
                  <groupId>org.jetbrains.kotlinx</groupId>
                  <artifactId>kotlinx-serialization-json-jvm</artifactId>
                  <version>1.6.3</version>
                </dependency>
                <dependency>
                  <groupId>org.jetbrains.kotlinx</groupId>
                  <artifactId>kotlinx-serialization-core</artifactId>
                  <version>1.6.3</version>
                </dependency>
              </dependencies>
            </project>
            """.trimIndent(),
        )
        Files.copy(
            repository.resolve(
                "org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.6.3/" +
                    "kotlinx-serialization-json-jvm-1.6.3.jar",
            ),
            serializationAlias.resolve("kotlinx-serialization-json-1.6.3.jar"),
            StandardCopyOption.REPLACE_EXISTING,
        )
        val serializationCoreAlias =
            repository
                .resolve("org/jetbrains/kotlinx/kotlinx-serialization-core/1.6.3")
        Files.createDirectories(serializationCoreAlias)
        Files.copy(
            repository.resolve(
                "org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.6.3/" +
                    "kotlinx-serialization-core-jvm-1.6.3.jar",
            ),
            serializationCoreAlias.resolve("kotlinx-serialization-core-1.6.3.jar"),
            StandardCopyOption.REPLACE_EXISTING,
        )
        Files.writeString(
            serializationCoreAlias.resolve("kotlinx-serialization-core-1.6.3.pom"),
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.jetbrains.kotlinx</groupId>
              <artifactId>kotlinx-serialization-core</artifactId>
              <version>1.6.3</version>
            </project>
            """.trimIndent(),
        )
    }
}

tasks.register("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
    finalizedBy("verifyExternalConsumer")
}

tasks.register<Exec>("verifyExternalConsumer") {
    group = "verification"
    description = "Exercises the installed CLI and published plugin from isolated temporary consumer directories."
    commandLine("bash", layout.projectDirectory.file("scripts/verify-external-consumer.sh").asFile.absolutePath)
}
