import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Maven Central publishing for every krispr artifact: the runtime, each compiler variant and the Gradle
 * plugin (whose marker also goes to the Gradle Plugin Portal through `com.gradle.plugin-publish`).
 *
 * `./gradlew publishToMavenLocal` needs no credentials. A release needs `mavenCentralUsername`,
 * `mavenCentralPassword`, `signingInMemoryKey` and `signingInMemoryKeyPassword` (Gradle properties or
 * `ORG_GRADLE_PROJECT_*` environment variables); signing is only switched on when a key is present, so
 * local publishing stays unsigned.
 */
class PublishConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Included in another build (the composite-build way of using krispr before a release), nothing is
        // published, and the publishing plugin needs Gradle 8.13+ where krispr itself does not.
        if (project.gradle.parent != null) return
        project.pluginManager.apply("com.vanniktech.maven.publish")
        project.extensions.getByType(MavenPublishBaseExtension::class.java).apply {
            publishToMavenCentral()
            if (project.providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
            pom { pom ->
                pom.name.set(project.name)
                pom.description.set(project.provider { project.description ?: "Krispr: IR-level mutation testing for Kotlin" })
                pom.url.set("https://github.com/timusus/krispr")
                pom.licenses { licenses ->
                    licenses.license { license ->
                        license.name.set("The Apache License, Version 2.0")
                        license.url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                pom.developers { developers ->
                    developers.developer { developer ->
                        developer.id.set("timusus")
                        developer.name.set("Tim Malseed")
                    }
                }
                pom.scm { scm ->
                    scm.url.set("https://github.com/timusus/krispr")
                    scm.connection.set("scm:git:https://github.com/timusus/krispr.git")
                    scm.developerConnection.set("scm:git:ssh://git@github.com/timusus/krispr.git")
                }
            }
        }
    }
}
