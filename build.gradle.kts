import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.cyclonedx)
}

group = "app.shareguard"
version = providers.gradleProperty("shareguardVersionName").get()

allprojects {
    group = rootProject.group
    version = rootProject.version

    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.withType<CyclonedxDirectTask>().configureEach {
        // The application runtime graph already includes every shipped project and
        // external dependency. Excluding test/benchmark graphs keeps the release
        // SBOM accurate and avoids network metadata enrichment during CI.
        enabled = project.path == ":app"
        if (project.path == ":app") {
            includeConfigs.set(listOf("releaseRuntimeClasspath"))
            includeMetadataResolution.set(false)
            includeBuildEnvironment.set(false)
            includeBuildSystem.set(false)
            includeBomSerialNumber.set(false)
            componentGroup.set("app.shareguard")
            componentName.set("ShareGuard")
            componentVersion.set(rootProject.version.toString())
            projectType.set(Component.Type.APPLICATION)
            jsonOutput.set(rootProject.layout.buildDirectory.file("reports/cyclonedx/bom.json"))
            xmlOutput.set(rootProject.layout.buildDirectory.file("reports/cyclonedx/bom.xml"))
        }
    }
}

tasks.register("quality") {
    group = "verification"
    description = "Runs all local static checks and unit tests."
    dependsOn(subprojects.filter { it.buildFile.exists() }.map { "${it.path}:check" })
}
