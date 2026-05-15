package com.applock1.app.data

import android.content.Context
import android.content.SharedPreferences

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

class PrefsManager(context: Context) {

    private val p: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "applock1_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // BUG FIX: On Samsung Knox, changing lock screen type can invalidate Keystore keys.
            // If the keystore is corrupted, delete the preferences file AND explicitly delete the Keystore alias.
            val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/applock1_secure_prefs.xml")
            if (prefsFile.exists()) prefsFile.delete()
            
            try {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            } catch (_: Exception) {}
            
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "applock1_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    var isGlobalEnabled: Boolean
        get() = p.getBoolean("global_on", true)
        set(v) = p.edit().putBoolean("global_on", v).apply()

    var hideNotificationContent: Boolean
        get() = p.getBoolean("hide_notif", true)
        set(v) = p.edit().putBoolean("hide_notif", v).apply()

    // In-memory fast cache for zero-latency cold start
    var fastLockedAppsCache: Set<String>
        get() = p.getStringSet("fast_cache", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("fast_cache", v).apply()

    enum class RelockDelay(val ms: Long, val label: String, val emoji: String) {
        IMMEDIATELY(    0L,       "Al salir",   "⚡"),
        SEC_30(    30_000L,   "30 segundos", "⏱"),
        MIN_1(     60_000L,   "1 minuto",    "1️⃣"),
        MIN_5(    300_000L,   "5 minutos",   "5️⃣"),
        MIN_15(   900_000L,   "15 minutos",  "🔟"),
        NEVER(  Long.MAX_VALUE, "Nunca",     "♾️")
    }

    var relockDelay: RelockDelay
        get() {
            val s = p.getString("relock", RelockDelay.IMMEDIATELY.name)
            return try { RelockDelay.valueOf(s!!) } catch (e: Exception) { RelockDelay.IMMEDIATELY }
        }
        set(v) = p.edit().putString("relock", v.name).apply()
        
    var requireAuthToOpen: Boolean
        get() = p.getBoolean("auth_to_open", false)
        set(v) = p.edit().putBoolean("auth_to_open", v).apply()

    enum class AppTheme(val label: String, val emoji: String) {
        SYSTEM("Sistema", "⚙️"),
        DARK("Oscuro", "🌙"),
        LIGHT("Claro", "☀️")
    }

    var appTheme: AppTheme
        get() {
            val s = p.getString("app_theme", AppTheme.SYSTEM.name)
            return try { AppTheme.valueOf(s!!) } catch (e: Exception) { AppTheme.SYSTEM }
        }
        set(v) = p.edit().putString("app_theme", v.name).apply()
}
