package dev.quietnet

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings switch for turning blocking on and off. */
class ToggleTile : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        if (Blocker.status.value != Status.OFF) {
            Blocker.stop(this)
            refresh(Status.OFF)
            return
        }
        // The VPN permission prompt needs an activity.
        if (VpnService.prepare(this) != null || !Blocker.start(this)) {
            openApp()
            return
        }
        refresh(Status.ON)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_ENABLE, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh(status: Status = Blocker.status.value) {
        val tile = qsTile ?: return
        tile.state = if (status == Status.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (status == Status.OFF) "Off" else "On"
        tile.updateTile()
    }
}
