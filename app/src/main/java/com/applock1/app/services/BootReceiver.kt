package com.applock1.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Android 7.0+ will automatically bind the AccessibilityService and 
            // NotificationListenerService if they were enabled by the user before reboot.
            // We just ensure we're lightly touching the database if needed, but 
            // the FastCache handles the instant boot anyway.
        }
    }
}
