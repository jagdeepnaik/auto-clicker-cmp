plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
}

kotlin {

    androidLibrary {
        namespace = "com.jagdeep.common"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTestBuilder {
        }

        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    val xcfName = "coreCommonKit"
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

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)

            }
        }

        androidMain {
            dependencies {
            }
        }

        iosMain {
            dependencies {
            }
        }

        jvmMain {
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

// 1. Define an abstract class for the task
abstract class GenerateAppConfigTask : DefaultTask() {

    // Input: The App Name string (captured safely)
    @get:Input
    abstract val appName: Property<String>

    @get:Input
    abstract val versionCode: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    // Output: The directory where the file is generated
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val appNameValue = appName.get()
        val appNameLowercase = appNameValue.lowercase()
        val appNameUppercase = appNameValue.uppercase()

        // Get the directory (e.g. build/generated/source/appConfig)
        val outDir = outputDir.get().asFile

        // Create the package path: com/jagdeep/common/config
        val pkgDir = File(outDir, "com/jagdeep/common/config")
        pkgDir.mkdirs()

        // Write the file content
        File(pkgDir, "AppConfig.kt").writeText(
            """
            package com.jagdeep.common.config

            object AppConfig {
                const val APP_NAME = "$appNameValue"
                const val APP_NAME_LOWERCASE = "$appNameLowercase"
                const val APP_NAME_UPPERCASE = "$appNameUppercase"
                const val APP_VERSION_CODE = "${versionCode.get()}"
                const val APP_VERSION_NAME = "${versionName.get()}"
            }
            """.trimIndent()
        )
    }
}

// 2. Register the task
val generateAppConfig = tasks.register<GenerateAppConfigTask>("generateAppConfig") {

    // 1. Get the Version Catalog
    val libs = project.extensions.getByType<VersionCatalogsExtension>().named("libs")

    // 2. Read values from [versions] in .toml
    // Note: "appName" in TOML becomes "libs.versions.appName"
    appName.set(libs.findVersion("appName").get().toString())
    versionCode.set(libs.findVersion("versionCode").get().toString())
    versionName.set(libs.findVersion("versionName").get().toString())

    // Set the output directory
    outputDir.set(layout.buildDirectory.dir("generated/source/appConfig"))
}

// 3. Register the generated source with Kotlin
kotlin {
    sourceSets {
        commonMain {
            // Add the generated directory to the source set
            kotlin.srcDir(generateAppConfig.map { it.outputDir })
        }
    }
}
