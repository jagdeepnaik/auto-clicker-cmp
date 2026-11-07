import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.serialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            binaryOption("bundleId", "com.jagdeep.autoclicker.composeapp")
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.stdlib)
            implementation(libs.components.resources)
            // Coroutines
            implementation(libs.kotlinx.coroutines.core)
            // Lifecycle
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            // Serialization
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.core)
            // Persistence
            implementation(libs.androidx.datastore)
            implementation(libs.androidx.datastore.preferences)
            // UI Preview
            implementation(libs.ui.tooling.preview)
            implementation(libs.material.icons.extended)

            // Local Core
            implementation(project(":core:automation"))
            implementation(project(":core:common"))
            implementation(project(":core:localbridge"))
            implementation(project(":core:logger"))
            implementation(project(":core:persistence"))
            implementation(project(":core:ui"))
            // Features
            implementation(project(":feature:activity"))
            implementation(project(":feature:home"))
            implementation(project(":feature:settings"))
            implementation(project(":feature:shared"))
        }

        androidMain.dependencies {
            implementation(libs.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            // Windows title bar colours (DWM).
            implementation(libs.jna.platform)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.dependencies {
            implementation(compose.desktop.uiTestJUnit4)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.jagdeep.autoclicker.MainKt"

        buildTypes.release.proguard {
            // Matches the template: shrinking is off until the dependency graph is known to survive it.
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AutoClicker"
            packageVersion = libs.versions.versionName.get()
            // JDK modules the packaged runtime must contain (from :composeApp:suggestRuntimeModules).
            // jdk.unsupported: sun.misc.Unsafe (libraries fail at launch without it).
            modules(
                "java.instrument",
                "java.prefs",
                // jdk.httpserver: the local link to the Chrome extension.
                "jdk.httpserver",
                "jdk.unsupported",
            )
            macOS {
                iconFile.set(project.file("app-icons/AutoClicker.icns"))
                bundleID = "com.jagdeep.autoclicker"
            }
            windows {
                iconFile.set(project.file("src/jvmMain/resources/autoclicker-icon.ico"))
                // Create both common Windows entry points during MSI installation.
                shortcut = true
                menuGroup = "AutoClicker"
                // Must remain stable across releases so MSI can upgrade the installed app.
                upgradeUuid = "ea4ff469-d036-4a9c-9b32-824d6e6e11f9"
            }
            linux {
                iconFile.set(project.file("app-icons/autoclicker-icon-linux-512.png"))
            }
        }
    }
}

// Release signing comes from the git-ignored keystore.properties at the project root.
// Without it (for example on CI) the release APK is built unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.jagdeep.autoclicker"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = libs.versions.androidApplicationId.get()
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.txt"
            )
        }
    }
    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.ui.tooling)
}
