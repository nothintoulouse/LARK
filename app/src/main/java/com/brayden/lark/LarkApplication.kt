package com.brayden.lark

import android.app.Application
import com.brayden.lark.service.NotificationHelper

class LarkApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
    }
}
