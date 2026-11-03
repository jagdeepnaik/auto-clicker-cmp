plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.serialization)
}

kotlin {
    androidLibrary { namespace = "com.jagdeep.pagehelper"; compileSdk = libs.versions.android.compileSdk.get().toInt(); minSdk = libs.versions.android.minSdk.get().toInt() }
    iosArm64 { binaries.framework { baseName = "featurePageHelperKit" } }
    iosSimulatorArm64 { binaries.framework { baseName = "featurePageHelperKit" } }
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.stdlib)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":core:automation"))
            implementation(project(":core:localbridge"))
            implementation(project(":core:logger"))
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
