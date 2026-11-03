plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
    alias(libs.plugins.serialization)
}

kotlin {
    androidLibrary {
        namespace = "com.jagdeep.transfer"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    iosArm64 { binaries.framework { baseName = "transferKit" } }
    iosSimulatorArm64 { binaries.framework { baseName = "transferKit" } }
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.stdlib)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.bouncycastle.prov)
            implementation(libs.bouncycastle.pkix)
            // Windows DPAPI for desktop secrets (no PowerShell, which security tools often block).
            implementation(libs.jna.platform)
        }
    }
}
