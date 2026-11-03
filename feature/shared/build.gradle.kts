plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
    alias(libs.plugins.serialization)
}

kotlin {
    androidLibrary {
        namespace = "com.jagdeep.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    iosArm64 { binaries.framework { baseName = "featureSharedKit" } }
    iosSimulatorArm64 { binaries.framework { baseName = "featureSharedKit" } }
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.stdlib)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.core)
            api(project(":core:automation"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
