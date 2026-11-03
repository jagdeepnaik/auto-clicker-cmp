plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidLibrary { namespace = "com.jagdeep.settings"; compileSdk = libs.versions.android.compileSdk.get().toInt(); minSdk = libs.versions.android.minSdk.get().toInt() }
    iosArm64 { binaries.framework { baseName = "featureSettingsKit" } }
    iosSimulatorArm64 { binaries.framework { baseName = "featureSettingsKit" } }
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.stdlib)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.ui.tooling.preview)
            implementation(project(":core:automation"))
            implementation(project(":core:persistence"))
            implementation(project(":core:ui"))
            implementation(project(":feature:shared"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(libs.kotlinx.coroutinesSwing)
        }
    }
}
