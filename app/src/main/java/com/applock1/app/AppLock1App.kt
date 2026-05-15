package com.applock1.app

import android.app.Application
import com.applock1.app.data.AppLockDatabase
import com.applock1.app.data.AppLockRepository
import com.applock1.app.data.PrefsManager

class AppLock1App : Application() {

    val prefs by lazy { PrefsManager(this) }
    private val db by lazy { AppLockDatabase.build(this) }
    val repo by lazy { AppLockRepository(db.dao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: AppLock1App
            private set
    }
}
