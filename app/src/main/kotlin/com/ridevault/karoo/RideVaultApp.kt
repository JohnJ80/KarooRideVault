package com.ridevault.karoo

import android.app.Application
import timber.log.Timber

class RideVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        Timber.i("RideVault started")
    }
}
