package com.applock1.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.applock1.app.AppLock1App
import com.applock1.app.LockOverlayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppLockAccessibilityService : AccessibilityService() {

    // Use SupervisorJob so one failed coroutine doesn't kill siblings
    // BUG FIX: Scope was never cancelled on onDestroy — on Samsung, service can be
    // destroyed and recreated rapidly causing coroutine leaks that fight each other.
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private set

    // In-memory locked apps cache (zero DB latency)
    @Volatile private var lockedApps = setOf<String>()

    // Session: once unlocked, don't re-lock until relockDelay elapses
    // ConcurrentHashMap required — accessed from both IO (polling) and Main (accessibility events) threads
    private val sessionUnlocked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // State flags
    private val lockPendingMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var screenOn = true
    @Volatile private var gracePkg: String? = null
    @Volatile private var graceUntil = 0L
    @Volatile private var pollingStarted = false  // BUG FIX: prevent duplicate polling loops on Samsung service restart

    private val prefs get() = AppLock1App.instance.prefs

    // ── Screen ON/OFF receiver ──────────────────────────────────────────────
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    lockPendingMap.clear()
                    gracePkg = null
                }
                Intent.ACTION_SCREEN_ON -> screenOn = true
            }
        }
    }

    // ── Companion ───────────────────────────────────────────────────────────
    companion object {
        var instance: AppLockAccessibilityService? = null
            private set

        // Called by LockOverlayActivity after successful biometric auth
        fun onUnlocked(pkg: String) {
            instance?.apply {
                sessionUnlocked[pkg] = System.currentTimeMillis()
                gracePkg = pkg
                graceUntil = System.currentTimeMillis() + 3_000L
                lockPendingMap.remove(pkg)
            }
            // FIX: Cancel our replacement notifications now that user has authenticated.
            // This prevents the "Contenido oculto" notification from lingering in the
            // status bar after the user opens and reads their messages.
            NotificationGuardService.instance?.cancelReplacementsFor(pkg)
        }

        // Called by LockOverlayActivity when user cancels (go home) or activity is destroyed
        fun resetLockForPkg(pkg: String) {
            instance?.lockPendingMap?.remove(pkg)
        }

        // Fallback for global reset
        fun resetLock() {
            instance?.lockPendingMap?.clear()
        }

        private val SKIP_PACKAGES = setOf(
            // ── Android core ───────────────────────────────────────────────
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.inputmethod.latin",

            // ── Samsung One UI 4/5/6/7/8 — System UI & Shell ──────────────
            "com.samsung.android.systemui",         // One UI shell
            "com.samsung.systemui.multiuser",
            "com.sec.android.app.launcher",         // One UI Home
            "com.samsung.android.app.launcher",
            "com.samsung.android.app.taskedge",     // Edge panels launcher
            "com.samsung.android.app.cocktailbarservice",
            "com.samsung.android.edgepanel",
            "com.sec.android.app.edge",

            // ── Samsung One UI — Calls & Communication ─────────────────────
            "com.samsung.android.incallui",         // Samsung dialer in-call
            "com.samsung.android.dialer",
            "com.android.phone",

            // ── Samsung One UI — Keyboard & Input ─────────────────────────
            "com.samsung.android.honeyboard",       // Samsung keyboard
            "com.samsung.android.emoji",

            // ── Samsung One UI — Bixby & AI ───────────────────────────────
            "com.samsung.android.bixby.agent",
            "com.samsung.android.bixby.wakeup",
            "com.samsung.android.bixby.service",
            "com.samsung.android.bixbyvision.framework",
            "com.samsung.android.app.spage",        // Bixby Home

            // ── Samsung One UI — Security & Device Care ────────────────────
            "com.samsung.android.sm.devicesecurity",
            "com.samsung.android.sm",               // Device Care
            "com.samsung.klmsagent",                // Knox License
            "com.samsung.android.knox.containercore",
            "com.samsung.knox.securefolder",        // Secure Folder
            "com.samsung.android.securitylogagent",

            // ── Samsung One UI — Game Launcher (One UI 4-8) ───────────────
            // CRÍTICO: Game Launcher dibuja su propia overlay; bloquearla
            // causa que el usuario no pueda acceder a juegos protegidos.
            "com.samsung.android.game.gos",         // Game Optimizing Service
            "com.samsung.android.game.gametools",   // Game Booster toolbar
            "com.samsung.android.game.gamehome",    // Game Launcher

            // ── Samsung One UI — DeX (escritorio) ─────────────────────────
            "com.sec.android.desktopmode.uiservice",
            "com.samsung.android.dex.systemui",

            // ── Samsung One UI — Accesibilidad y Bienestar ────────────────
            "com.samsung.accessibility",
            "com.samsung.android.wellbeing",
            "com.samsung.android.app.screenrecorder",

            // ── Samsung One UI — Otros servicios del sistema ───────────────
            "com.samsung.android.app.omcagent",
            "com.sec.android.daemonapp",
            "com.samsung.android.providers.context",
            "com.samsung.android.sm.bbc",

            // ── Launchers genéricos (fallback) ─────────────────────────────
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.microsoft.launcher",

            // ── Realme / OPPO (ya en el proyecto, mantener) ───────────────
            "com.coloros.launcher",
            "com.oppo.launcher",
            "com.realme.launcher",
        )
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────
    override fun onServiceConnected() {
        instance = this
        sessionUnlocked.clear()
        lockPendingMap.clear()
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS  // Required for One UI DeX & split-screen
            notificationTimeout = 50
        }

        // Android 12+ (API 31) requires explicit RECEIVER_NOT_EXPORTED for non-system broadcasts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
        }

        // Warm cache from prefs (instant, no DB wait)
        lockedApps = prefs.fastLockedAppsCache

        // Keep cache in sync with DB
        scope.launch {
            AppLock1App.instance.repo.lockedApps.collect { list ->
                val set = list.map { it.packageName }.toSet()
                lockedApps = set
                prefs.fastLockedAppsCache = set
            }
        }

        // BUG FIX: Guard against duplicate polling loops on Samsung service restarts
        if (!pollingStarted) {
            pollingStarted = true
            startPolling()
        }
    }

    override fun onDestroy() {
        // BUG FIX: Cancel scope on destroy to prevent coroutine leaks.
        // On Samsung One UI, the AccessibilityService can be killed and restarted
        // by the system without calling onCreate again, causing multiple polling
        // loops to coexist and fight each other, draining battery.
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pollingStarted = false
        instance = null
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
    }

    override fun onInterrupt() {
        // BUG FIX: Also clear grace state on interrupt, not just lockPending.
        // Without this, if Samsung interrupts the service mid-lock, gracePkg
        // stays set and the NEXT attempt to open that app is silently skipped.
        lockPendingMap.clear()
        gracePkg = null
    }

    // ── Polling (UsageStats) ────────────────────────────────────────────────
    private fun startPolling() = scope.launch {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val event = UsageEvents.Event()
        while (true) {
            delay(150)
            if (!screenOn || !prefs.isGlobalEnabled || lockedApps.isEmpty()) continue

            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 1_500, now)
            var top: String? = null
            var topTs = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp > topTs) {
                    top = event.packageName
                    topTs = event.timeStamp
                }
            }
            if (top != null) tryLock(top, topTs)
        }
    }

    // ── Accessibility events ────────────────────────────────────────────────
    override fun onAccessibilityEvent(ev: AccessibilityEvent?) {
        val pkg = ev?.packageName?.toString() ?: return
        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!prefs.isGlobalEnabled) return
        // Let tryLock handle SKIP_PACKAGES so it can detect when the user exits to the launcher!
        tryLock(pkg, System.currentTimeMillis())
    }

    // ── Lock evaluation ─────────────────────────────────────────────────────
    @Volatile private var currentFgPkg = ""
    @Volatile private var currentFgTs = 0L

    // BUG FIX: Track when we exit an app to debounce trailing animations on Samsung.
    // If the system says we "re-entered" an app less than 500ms after leaving it, it's a false alarm.
    private val lastExitTime = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // BUG FIX: The sessionUnlocked map is defined at the top of the class as a
    // ConcurrentHashMap to avoid thread-safety issues between IO/Main threads on Samsung.
    
    // System UI overlays (keyboards, edge panels, volume sliders) that pop up 
    // ON TOP of apps but shouldn't be considered "leaving" the app.
    private val SYSTEM_OVERLAYS = setOf(
        "com.android.systemui", "com.samsung.android.systemui", 
        "com.samsung.android.honeyboard", "com.android.inputmethod.latin",
        "com.samsung.android.app.taskedge", "com.samsung.android.edgepanel",
        "com.sec.android.app.edge", "android"
    )

    private fun tryLock(pkg: String, eventTs: Long) {
        // BUG FIX: If the new event is just a keyboard or edge panel popping up,
        // ignore it completely so we don't think the user "left" the protected app.
        if (SYSTEM_OVERLAYS.contains(pkg)) return

        val now = System.currentTimeMillis()

        // BUG FIX: Debounce phantom/trailing window events from Samsung animations.
        // When the user presses Home, Samsung fires a ghost ACTIVITY_RESUMED event for the
        // closing app with an OLD timestamp (≤ the moment we exited). A real re-entry will
        // always have a FRESH timestamp (> exit time). Comparing eventTs vs exitTime lets us
        // distinguish phantom animation events from legitimate fast re-entries perfectly,
        // without blocking users who open protected apps quickly.
        val exitTime = lastExitTime[pkg] ?: 0L
        if (pkg != currentFgPkg && exitTime > 0L && eventTs <= exitTime + 100L) {
            return
        }

        // ── Detect foreground change FIRST (even for launchers/system) ─────
        // This is critical: if the user switches to the launcher (which is in
        // SKIP_PACKAGES), we still need to clear their unlocked session so the
        // locked app re-prompts when they come back.
        if (pkg != currentFgPkg) {
            // BUG FIX: Prevent UsageStats (polling) from overriding a more recent Accessibility event.
            // If the event timestamp is older than our last known change, it's stale data.
            if (eventTs < currentFgTs) return

            val prev = currentFgPkg
            if (prev.isNotEmpty()) {
                lastExitTime[prev] = now
            }
            
            currentFgPkg = pkg
            currentFgTs = eventTs
            val delayMs = prefs.relockDelay.ms
            if (delayMs == 0L && prev.isNotEmpty() && !SKIP_PACKAGES.contains(prev)) {
                // IMMEDIATELY: clear session the moment user leaves the app
                sessionUnlocked.remove(prev)
            }

            // ── Recents privacy shield ────────────────────────────────────────
            // Samsung fires TYPE_WINDOWS_CHANGED (not STATE_CHANGED) for the task switcher,
            // so class-name detection is unreliable. Instead: the moment the user leaves a
            // locked app with an active session heading to ANY system package, show the shield
            // immediately. This covers the thumbnail in recents before it becomes visible.
            // The shield hides automatically when the user lands on a real app (session paths
            // in tryLock call FastShieldManager.hide()).
            val SYSTEMUI_PKGS = setOf("com.android.systemui", "com.samsung.android.systemui")
            if (SYSTEMUI_PKGS.contains(pkg) &&
                prev.isNotEmpty() &&
                lockedApps.contains(prev) &&
                sessionUnlocked.containsKey(prev)) {
                scope.launch(Dispatchers.Main) {
                    FastShieldManager.show(this@AppLockAccessibilityService)
                    // Auto-hide after 2s in case the user dismissed recents without selecting an app
                    delay(2_000)
                    FastShieldManager.hide()
                }
            }
        }

        // Now apply skip rules (after fg tracking)
        if (SKIP_PACKAGES.contains(pkg)) return
        if (pkg == packageName) return
        if (!prefs.isGlobalEnabled) return

        // BUG FIX: Auto-expire a stuck lockPending after 3 seconds for this package.
        // If the user exits quickly before the lock screen appears, Android can
        // kill LockOverlayActivity without calling onDestroy, leaving lockPending=true forever.
        val pendingTs = lockPendingMap[pkg]
        if (pendingTs != null && (now - pendingTs) > 3_000L) {
            lockPendingMap.remove(pkg)
        }
        if (lockPendingMap.containsKey(pkg)) return

        // Not a locked app → nothing to do
        if (!lockedApps.contains(pkg)) return

        // ── Session / relock delay check ──────────────────────────────────
        val lastUnlock = sessionUnlocked[pkg] ?: 0L
        val delayMs    = prefs.relockDelay.ms
        if (lastUnlock > 0L) {
            when {
                delayMs == Long.MAX_VALUE -> { FastShieldManager.hide(); return }   // NEVER re-lock
                delayMs == 0L            -> { FastShieldManager.hide(); return }   // IMMEDIATELY: in-session
                now - lastUnlock < delayMs -> { FastShieldManager.hide(); return } // Within time window
                else -> sessionUnlocked.remove(pkg)         // Expired: clear and proceed to lock
            }
        }

        // ── Grace period safety net ───────────────────────────────────────
        if (gracePkg == pkg && now < graceUntil) return

        // ── Trigger lock ──────────────────────────────────────────────────
        lockPendingMap[pkg] = now
        scope.launch(Dispatchers.Main) {
            FastShieldManager.show(this@AppLockAccessibilityService)
            startActivity(Intent(this@AppLockAccessibilityService, LockOverlayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra(LockOverlayActivity.PKG_KEY, pkg)
            })
        }
    }
}
