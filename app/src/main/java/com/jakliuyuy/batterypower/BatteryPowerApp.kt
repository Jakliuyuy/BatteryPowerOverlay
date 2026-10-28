package com.jakliuyuy.batterypower

import android.app.Application
import com.jakliuyuy.batterypower.settings.AppContext

class BatteryPowerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
    }
}
