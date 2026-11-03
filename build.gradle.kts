import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.resources.ResourcesExtension

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.androidLint) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
}

/**
 * core:ui is an exact copy of the template, and its AppIcon imports the generated `Res` from the
 * package the template's root project name produces. Pinning that package here lets the copy stay
 * byte-identical while this project keeps its own name.
 */
project(":core:ui") {
    plugins.withId("org.jetbrains.compose") {
        val compose = extensions.getByType<ComposeExtension>() as ExtensionAware
        compose.extensions.configure<ResourcesExtension> {
            packageOfResClass = "zynk.core.ui.generated.resources"
        }
    }
}

/**
 * Feature modules may use core modules, but never a concrete sibling feature.
 * The app shell is the only composition root.
 */
tasks.register("verifyFeatureModuleBoundaries") {
    group = "verification"
    description = "Rejects dependencies between concrete feature modules (feature:shared is allowed)."
    notCompatibleWithConfigurationCache("Scans feature build scripts when the task executes.")
    doLast {
        val featureDependency = Regex("""project\("(:feature:[^"]+)"\)""")
        val violations = fileTree("feature") {
            include("**/build.gradle.kts")
        }.files.flatMap { file ->
            featureDependency.findAll(file.readText())
                .map { match -> match.groupValues[1] }
                .filter { dependency -> dependency != ":feature:shared" }
                .map { dependency -> "${file.relativeTo(projectDir)} depends on $dependency" }
                .toList()
        }
        check(violations.isEmpty()) {
            "Concrete feature modules must not depend on each other.\n${violations.joinToString("\n")}"
        }
    }
}
