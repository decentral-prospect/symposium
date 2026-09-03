import org.cyclonedx.gradle.CyclonedxAggregateTask
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import java.util.Properties

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false

    id("org.cyclonedx.bom") version "3.4.1"
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

val symposiumVersion = versionProperties.getProperty("VERSION_NAME")
    ?: error("VERSION_NAME is missing from version.properties")

project(":app") {
    tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
        includeConfigs = listOf("releaseRuntimeClasspath")

        projectType = Component.Type.APPLICATION
        componentName = "Symposium"
        componentVersion = symposiumVersion

        xmlOutput.unsetConvention()
    }
}

tasks.named<CyclonedxAggregateTask>("cyclonedxBom") {
    projectType = Component.Type.APPLICATION
    componentName = "Symposium"
    componentVersion = symposiumVersion

    xmlOutput.unsetConvention()
}
