package com.applock1.app.services

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.applock1.app.AppLock1App

@RequiresApi(Build.VERSION_CODES.N)
class AppLockTileService : TileService() {
    override fun onStartListening() = updateTile()
    override fun onClick() {
        if (AppLock1App.instance.prefs.requireAuthToOpen) {
            unlockAndRun {
                AppLock1App.instance.prefs.isGlobalEnabled = !AppLock1App.instance.prefs.isGlobalEnabled
                updateTile()
            }
        } else {
            AppLock1App.instance.prefs.isGlobalEnabled = !AppLock1App.instance.prefs.isGlobalEnabled
            updateTile()
        }
    }
    private fun updateTile() {
        val on = AppLock1App.instance.prefs.isGlobalEnabled
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "AppLock1"
            subtitle = if (on) "Activo" else "Pausado"
            icon = Icon.createWithResource(this@AppLockTileService,
                if (on) android.R.drawable.ic_secure else android.R.drawable.ic_lock_idle_lock)
            updateTile()
        }
    }
}
