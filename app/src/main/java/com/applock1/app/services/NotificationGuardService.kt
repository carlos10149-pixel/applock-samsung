package com.applock1.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.applock1.app.AppLock1App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class NotificationGuardService : NotificationListenerService() {

    companion object {
        // Live reference so AppLockAccessibilityService can call cancelReplacementsFor()
        @Volatile var instance: NotificationGuardService? = null
            private set

        // Prefixes of Samsung system packages that must never be intercepted.
        // One UI will fight the service in an infinite loop if we try to cancel these.
        private val SAMSUNG_SYSTEM_PKGS = listOf(
            "com.samsung.android.systemui",
            "com.samsung.android.app.",
            "com.samsung.android.sm",
            "com.samsung.android.bixby",
            "com.samsung.android.game",
            "com.sec.android.",
            "com.samsung.android.knox",
            "com.samsung.knox",
        )

        // Orphan channels from previous buggy versions — delete these on connect.
        // Android permanently ignores importance/visibility changes to existing channels,
        // so the only fix is to delete the old channel and create a fresh one.
        private val ORPHAN_CHANNEL_SUFFIXES = listOf("_guarded", "_guarded_v2")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs get() = AppLock1App.instance.prefs

    // FIX 1: Track our replacement notification ID per original sbn.key.
    // Previously all notifications from the same app shared pkg.hashCode() as their ID,
    // causing each new message to silently overwrite the previous one in the status bar.
    private val postedIds = ConcurrentHashMap<String, Int>()
    private val idCounter = AtomicInteger(10_000)

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        // FIX 3: Delete orphan channels from previous buggy versions.
        scope.launch(Dispatchers.IO) {
            try {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val installedPkgs = packageManager
                    .getInstalledApplications(PackageManager.GET_META_DATA)
                    .map { it.packageName }
                for (pkg in installedPkgs) {
                    for (suffix in ORPHAN_CHANNEL_SUFFIXES) {
                        val channelId = "$pkg$suffix"
                        if (nm.getNotificationChannel(channelId) != null) {
                            nm.deleteNotificationChannel(channelId)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    // Called by AppLockAccessibilityService.onUnlocked() when the user authenticates.
    // Cancels all our replacement notifications for that package so they don't linger
    // after the user opens and reads their messages.
    fun cancelReplacementsFor(pkg: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            // sbn.key format is "userId|packageName|tag|id|..." — match by package segment
            val keysToRemove = postedIds.keys.filter { key ->
                key.contains("|$pkg|") || key.startsWith("0|$pkg|")
            }
            for (key in keysToRemove) {
                val id = postedIds.remove(key) ?: continue
                nm.cancel(id)
            }
        } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        if (!prefs.isGlobalEnabled || !prefs.hideNotificationContent) return

        // Ignore Samsung system packages — One UI manages these independently
        if (SAMSUNG_SYSTEM_PKGS.any { pkg.startsWith(it) }) return

        // Ignore persistent notifications (Ongoing / Foreground Service) to prevent loops on One UI
        val original = sbn.notification ?: return
        if ((original.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        if ((original.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        // Ignore media session notifications (music players, etc.)
        if (original.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return

        // Fast path: check in-memory cache
        val cache = AppLock1App.instance.prefs.fastLockedAppsCache
        if (!cache.contains(pkg)) return

        // Cancel original immediately to hide the content
        try {
            cancelNotification(sbn.key)
        } catch (_: Exception) {}

        // Post sanitized replacement asynchronously (IO avoids Main thread freezes/hibernation)
        scope.launch(Dispatchers.IO) {
            postReplacement(sbn, pkg)
        }
    }

    // FIX 2: When the original notification is removed (user opens app, WhatsApp marks
    // a conversation as read, etc.), cancel our replacement so it doesn't linger.
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val ourId = postedIds.remove(sbn.key) ?: return
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ourId)
        } catch (_: Exception) {}
    }

    private fun postReplacement(sbn: StatusBarNotification, pkg: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val original = sbn.notification ?: return

        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) { pkg }

        val channelId = "${pkg}_guarded_v3"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "$appName protegida", NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                }
            )
        }

        val n = Notification.Builder(this, channelId)
            .setSmallIcon(com.applock1.app.R.drawable.ic_launcher_foreground)
            .setContentTitle(appName)
            .setContentText("Contenido oculto por AppLock")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setGroup(sbn.groupKey ?: pkg)
            .also { b -> original.contentIntent?.let { b.setContentIntent(it) } }
            .build()

        // FIX 1: Each message gets a unique integer ID so multiple unread
        // messages all appear in the notification tray simultaneously.
        val uniqueId = idCounter.incrementAndGet()
        postedIds[sbn.key] = uniqueId
        nm.notify(uniqueId, n)
    }
}
