package com.jagdeep.autoclicker

import android.app.Application
import com.jagdeep.logger.Log
import com.jagdeep.logger.initLoggerAndroid

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        applicationContextInstance = applicationContext
        initLoggerAndroid(applicationContext)
        Log.print("-------------> Android App Start <-------------", "Android App")
    }

    companion object {
        lateinit var applicationContextInstance: android.content.Context
            private set
    }
}
