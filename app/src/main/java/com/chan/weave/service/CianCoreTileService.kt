package com.chan.weave.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.chan.weave.CianCoreApp
import com.chan.weave.MainActivity
import com.chan.weave.R
import com.chan.weave.mist.MistVpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile: tap to toggle the Mist tunnel.
 * Shows active state when connected.
 * Mist-only since 1.0.30 (previously read the Xray service state).
 */
class CianCoreTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val connected = MistVpnController.state == MistVpnController.State.CONNECTED
            // Show the selected node name on the tile, like v2rayNG does.
            val nodeName = try {
                val dao = CianCoreApp.instance.database.nodeDao()
                withContext(Dispatchers.IO) {
                    dao.getSelected()?.name
                }
            } catch (_: Throwable) { null }
            updateTile(connected, nodeName)
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val connected = MistVpnController.state == MistVpnController.State.CONNECTED
            if (connected) {
                MistVpnController.stop(this@CianCoreTileService)
                updateTile(false)
                return@launch
            }
            // Not connected: connect the selected Mist node, or fall
            // back to opening the app when there is none.
            val node = try {
                val dao = CianCoreApp.instance.database.nodeDao()
                withContext(Dispatchers.IO) { dao.getSelected() }
            } catch (_: Throwable) { null }
            if (node != null && node.rawLink.trim().startsWith("mist://")) {
                if (MistVpnController.isPrepared(this@CianCoreTileService)) {
                    MistVpnController.start(this@CianCoreTileService, node.rawLink.trim())
                    updateTile(true, node.name)
                } else {
                    // VPN permission not granted: open the app so the
                    // system permission dialog can be shown.
                    val intent = Intent(this@CianCoreTileService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivityAndCollapse(intent)
                }
            } else {
                val intent = Intent(this@CianCoreTileService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivityAndCollapse(intent)
            }
        }
        // For Android 13+, request the system to keep the tile updated.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // no-op: onStartListening covers refreshes
        }
    }

    private fun updateTile(connected: Boolean, nodeName: String? = null) {
        qsTile?.let { tile ->
            tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            // Show node name like v2rayNG: tap toggles that node.
            tile.label = nodeName ?: getString(R.string.app_name)
            tile.subtitle = if (connected) "已连接" else "未连接"
            try {
                tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_vpn)
            } catch (_: Throwable) {
            }
            tile.updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
