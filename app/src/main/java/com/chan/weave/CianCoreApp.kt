package com.chan.weave

import android.app.Application
import com.chan.weave.data.AppDatabase

/**
 * CianCore application entry point.
 * Owns the Room database singleton.
 */
class CianCoreApp : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: CianCoreApp
            private set
    }
}

