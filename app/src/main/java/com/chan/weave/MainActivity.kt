package com.chan.weave

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.chan.weave.ui.MainViewModel
import com.chan.weave.ui.navigation.CianCoreNavGraph
import com.chan.weave.ui.theme.CianCoreTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.onVpnPermissionGranted()
            vm.onShortcutVpnPermissionGranted()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* Foreground-service notifications still show without it. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Android 13+: ask for POST_NOTIFICATIONS once so the VPN
        // foreground notification is always visible.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Handle node shortcut: connect directly to the specified node.
        handleShortcutIntent()
        setContent {
            CianCoreTheme {
                CianCoreNavGraph(
                    vm = vm,
                    requestVpnPermission = { intent ->
                        vpnPermissionLauncher.launch(intent)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent()
    }

    private fun handleShortcutIntent() {
        val nodeId = intent?.getLongExtra(
            com.chan.weave.data.NodeShortcutManager.EXTRA_CONNECT_NODE_ID, -1L
        ) ?: -1L
        if (nodeId != -1L) {
            // Clear the extra so it doesn't re-trigger on rotation.
            intent?.removeExtra(com.chan.weave.data.NodeShortcutManager.EXTRA_CONNECT_NODE_ID)
            vm.connectToNode(nodeId) { permissionIntent ->
                vpnPermissionLauncher.launch(permissionIntent)
            }
        }
    }
}
