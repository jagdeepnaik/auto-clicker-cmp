plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.serialization)
}

kotlin {
    androidLibrary { namespace = "com.jagdeep.activity"; compileSdk = libs.versions.android.compileSdk.get().toInt(); minSdk = libs.versions.android.minSdk.get().toInt() }
    iosArm64 { binaries.framework { baseName = "featureActivityKit" } }
    iosSimulatorArm64 { binaries.framework { baseName = "featureActivityKit" } }
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.stdlib)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.kotlinx.serialization.json)
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
