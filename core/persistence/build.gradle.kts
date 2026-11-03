plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.serialization)
}

kotlin {

    // Target declarations - add or remove as needed below. These define
    // which platforms this KMP module supports.
    // See: https://kotlinlang.org/docs/multiplatform-discover-project.html#targets
    androidLibrary {
        namespace = "com.jagdeep.persistence"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        lint {
            abortOnError = false
            checkReleaseBuilds = false
            disable += setOf("RestrictedApi", "InvalidPackage")
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
    val xcfName = "corePersistenceKit"
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
                implementation(libs.kotlinx.serialization.json)

                // Datastore
                implementation(libs.androidx.datastore)
                implementation(libs.androidx.datastore.preferences)

                // Room Database
                api(libs.androidx.room.runtime)
                implementation(libs.androidx.sqlite.bundled)

                // Local
                implementation(project(":core:common"))
                implementation(project(":core:logger"))
            }
        }

        androidMain {
            dependencies {
                // Room Database
                implementation(libs.androidx.room.sqlite.wrapper)
                implementation(libs.androidx.room.ktx)
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

        jvmTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
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

// Room schemas directory
room {
// Room will generate migration schemas here
    schemaDirectory("$projectDir/schemas")
}

// Room compiler dependencies
dependencies {
// Android
    add("kspAndroid", libs.androidx.room.compiler)

// iOS
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)

// JVM (Desktop)
    add("kspJvm", libs.androidx.room.compiler)

// If you later add wasm/desktop/native:
// add("kspJvmMain", libs.androidx.room.compiler)
// add("kspWasmJs", libs.androidx.room.compiler)
}
