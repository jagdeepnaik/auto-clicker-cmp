rootProject.name = "AutoClicker"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":composeApp")
include(":core:common")
include(":core:ui")
include(":core:logger")
include(":core:network")
include(":core:persistence")
include(":core:notifications")
include(":core:transfer")
include(":core:automation")
include(":core:localbridge")
include(":feature:shared")
include(":feature:home")
include(":feature:activity")
include(":feature:pagehelper")
include(":feature:settings")
