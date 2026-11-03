package com.jagdeep.common.platform

interface Platform {

    enum class Type {
        ANDROID,
        IOS,
        JVM,
        WEB
    }

    enum class SubType {
        ANDROID_PHONE,
        ANDROID_TV,
        ANDROID_WATCH,
        IOS_PHONE,
        IOS_TV,
        IOS_WATCH,
        JVM_DESKTOP,
    }

    val name: String
    val version: String
    val type: Type
}

/*
KMP only distinguishes targets, not device categories:

Default Kotlin targets:
    androidMain → ALL Android devices (phone, tablet, TV, Wear)
    iosMain → ALL iOS devices (iPhone, iPad)
    jvmMain
    desktopMain
    webMain
* */