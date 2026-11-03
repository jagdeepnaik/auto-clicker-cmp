plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
    alias(libs.plugins.serialization)
}

kotlin {

    // Target declarations - add or remove as needed below. These define
    // which platforms this KMP module supports.
    // See: https://kotlinlang.org/docs/multiplatform-discover-project.html#targets
    androidLibrary {
        namespace = "com.jagdeep.logger"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // compileSdk = 36
        // minSdk = 26

        withHostTestBuilder {
        }

        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // For iOS targets, this is also where you should
    // configure native binary output. For more information, see:
    // https://kotlinlang.org/docs/multiplatform-build-native-binaries.html#build-xcframeworks

    // A step-by-step guide on how to include this library in an XCode
    // project can be found here:
    // https://developer.android.com/kotlin/multiplatform/migrate
    val xcfName = "coreLoggerKit"
    iosArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    jvm()

    // Source set declarations. Common to share sources between related targets.
    // See: https://kotlinlang.org/docs/multiplatform-hierarchy.html
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.logging.napier)

                // Local
                implementation(project(":core:common"))
            }
        }

        androidMain {
            dependencies {
                //implementation(libs.firebase.crashlytics.ktx)
            }
        }

        iosMain {
            dependencies {
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        getByName("androidDeviceTest") {
            dependencies {
                implementation(libs.androidx.runner)
                implementation(libs.androidx.core)
                implementation(libs.androidx.testExt.junit)
            }
        }
    }

}

/////////////////////////
//  GENERATED FILE SETUP
/////////////////////////

abstract class GenerateLogConfigTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val versionCatalogFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val pkgDir = File(outputDir.get().asFile, "com/jagdeep/logger")
        pkgDir.mkdirs()

        val toml = versionCatalogFile.get().asFile.readText()
        val selectedProfile = toml.versionValue("logConfigProfile").normalizedProfile()
        val selectedConfig = when (selectedProfile) {
            "debug" -> toml.versionValue("logDebugConfig").asLogConfig("logDebugConfig")
            "release" -> toml.versionValue("logReleaseConfig").asLogConfig("logReleaseConfig")
            else -> throw GradleException("Unexpected logConfigProfile value '$selectedProfile'.")
        }

        File(pkgDir, "LogConfig.kt").writeText(
            """
            package com.jagdeep.logger

            data class LogConfig(
                val consoleLoggingEnabled: Boolean = ${selectedConfig.consoleLoggingEnabled},
                val fileLoggingEnabled: Boolean = ${selectedConfig.fileLoggingEnabled},
                val harLoggingEnabled: Boolean = ${selectedConfig.harLoggingEnabled},
                val keepDeviceDisplayOn: Boolean = ${selectedConfig.keepDeviceDisplayOn},
                // Required for HAR plugin init.
                val harCapturePluginEnabled: Boolean = ${selectedConfig.harCapturePluginEnabled}
            )
            """.trimIndent()
        )
    }

    private fun String.versionValue(key: String): String {
        val pattern = Regex("""(?m)^\s*${Regex.escape(key)}\s*=\s*"([^"]*)"""")
        return pattern.find(this)?.groupValues?.get(1)
            ?: throw GradleException("Missing $key in gradle/libs.versions.toml.")
    }

    private fun String.normalizedProfile(): String {
        val profile = lowercase()
        if (profile != "debug" && profile != "release") {
            throw GradleException("Expected logConfigProfile to be \"debug\" or \"release\" but found '$this'.")
        }
        return profile
    }

    private fun String.asLogConfig(propertyName: String): LogConfigValue {
        val values = split(",")
            .filter { it.isNotBlank() }
            .associate { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.size != 2) {
                    throw GradleException("Expected $propertyName entry '$entry' to use key=value.")
                }
                parts[0].trim() to parts[1].trim()
            }

        val requiredKeys = setOf("console", "file", "har", "keepDisplayOn", "harCapturePlugin")
        val unknownKeys = values.keys - requiredKeys
        val missingKeys = requiredKeys - values.keys
        if (unknownKeys.isNotEmpty()) {
            throw GradleException("Unknown $propertyName keys: ${unknownKeys.joinToString()}.")
        }
        if (missingKeys.isNotEmpty()) {
            throw GradleException("Missing $propertyName keys: ${missingKeys.joinToString()}.")
        }

        fun boolean(key: String): Boolean =
            values.getValue(key).toBooleanStrictOrNull()
                ?: throw GradleException("Expected $propertyName key '$key' to be true or false.")

        return LogConfigValue(
            consoleLoggingEnabled = boolean("console"),
            fileLoggingEnabled = boolean("file"),
            harLoggingEnabled = boolean("har"),
            keepDeviceDisplayOn = boolean("keepDisplayOn"),
            harCapturePluginEnabled = boolean("harCapturePlugin"),
        )
    }

    private data class LogConfigValue(
        val consoleLoggingEnabled: Boolean,
        val fileLoggingEnabled: Boolean,
        val harLoggingEnabled: Boolean,
        val keepDeviceDisplayOn: Boolean,
        val harCapturePluginEnabled: Boolean,
    )
}

val generateLogConfig = tasks.register<GenerateLogConfigTask>("generateLogConfig") {
    versionCatalogFile.set(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml"))

    outputDir.set(layout.buildDirectory.dir("generated/source/logConfig"))
    outputs.upToDateWhen { false }
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(generateLogConfig.map { it.outputDir })
        }
    }
}
