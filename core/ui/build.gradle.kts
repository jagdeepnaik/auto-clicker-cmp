import org.gradle.kotlin.dsl.dependencies

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.serialization)
}

kotlin {

    androidLibrary {
        namespace = "com.jagdeep.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // Package Compose resources (e.g. the app logo) into apps that use this library.
        androidResources {
            enable = true
        }

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
    val xcfName = "coreUiKit"
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

    // Source set declarations.
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.components.resources)
                implementation(libs.kotlinx.coroutines.core)
                implementation(project(":core:common"))

                // Serialization
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.core)

                // UI Preview
                implementation(libs.ui.tooling.preview)

                // Compose | UI
                api(libs.ui)
                api(libs.runtime)
                api(libs.foundation)

                // Material 3
                api(libs.material3)
                api(libs.material.icons.extended)

                // Network Image Loader
                api(libs.coil3.compose)
                api(libs.coil3.network.ktor3)

                // Navigation 3
                api(libs.androidx.navigation3.ui)
                api(libs.androidx.material3.adaptive)
                api(libs.androidx.material3.adaptive.navigation3)
                api(libs.androidx.lifecycle.viewmodel.navigation3)
                // implementation(libs.androidx.navigation.compose) // Nav2
            }
        }

        androidMain {
            dependencies {
                implementation(libs.ui.tooling.preview)
                implementation(libs.androidx.activity.compose)
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
