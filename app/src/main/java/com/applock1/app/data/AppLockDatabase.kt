package com.applock1.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SupportFactory
import java.security.SecureRandom

@Database(entities = [LockedAppEntity::class], version = 1, exportSchema = false)
abstract class AppLockDatabase : RoomDatabase() {

    abstract fun dao(): AppLockDao

    companion object {
        @Volatile private var INSTANCE: AppLockDatabase? = null

        fun build(context: Context): AppLockDatabase = INSTANCE ?: synchronized(this) {
            // Generate and persist a random SQLCipher passphrase using EncryptedSharedPreferences
            val prefs = try {
                val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build()
                androidx.security.crypto.EncryptedSharedPreferences.create(
                    context,
                    "db_secure_key",
                    masterKey,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // BUG FIX: On Samsung Knox, Keystore keys can be silently invalidated.
                // If this happens, we lose the DB encryption password. SQLCipher will crash
                // if we try to open the old DB with a new password. We MUST delete the old DB file.
                val prefsFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/db_secure_key.xml")
                if (prefsFile.exists()) prefsFile.delete()
                
                context.deleteDatabase("applock.db")
                
                try {
                    val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                    ks.load(null)
                    ks.deleteEntry(androidx.security.crypto.MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                } catch (_: Exception) {}

                val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build()
                androidx.security.crypto.EncryptedSharedPreferences.create(
                    context,
                    "db_secure_key",
                    masterKey,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
            
            val oldPrefs = context.getSharedPreferences("db_key", Context.MODE_PRIVATE)
            val oldKey = oldPrefs.getString("key", null)
            
            val key = prefs.getString("key", null) ?: run {
                if (oldKey != null) {
                    // Migrate old key to secure storage and delete old plain text key
                    prefs.edit().putString("key", oldKey).apply()
                    oldPrefs.edit().clear().apply()
                    oldKey
                } else {
                    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        .also { prefs.edit().putString("key", it).apply() }
                }
            }
            Room.databaseBuilder(context.applicationContext, AppLockDatabase::class.java, "applock.db")
                .openHelperFactory(SupportFactory(key.toByteArray()))
                .fallbackToDestructiveMigration(false)
                .build()
                .also { INSTANCE = it }
        }
    }
}
