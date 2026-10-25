package com.chan.weave.mist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import com.chan.weave.data.PerAppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mobile.MobileEngine

/**
 * VpnService for the independent Mist framework (no Xray dependency).
 *
 * TUN setup (dual stack, all traffic through the tunnel):
 * - IPv4 address 10.0.0.2/32, IPv6 address fd00::2/128
 * - Routes 0.0.0.0/0 and ::/0, MTU 1400
 * - DNS 8.8.8.8 + 2001:4860:4860::8888 (DNS goes through the tunnel;
 *   the engine forwards UDP 53 like any other UDP flow)
 *
 * FD ownership: Builder.establish() returns a ParcelFileDescriptor.
 * We call detachFd() and hand the raw fd to Go (MobileEngine.start).
 * Go owns and closes it on stop(); Kotlin must NOT close it after
 * detach. If establish() fails, nothing is handed over.
 *
 * Routing-loop stopgap: the Go process's own UDP socket to the Mist
 * server would be routed back into the TUN (0.0.0.0/0 route) and
 * loop forever, because MistUDPClient dials with net.ListenUDP and
 * has no Android protect() hook. So we exclude our own app with
 * addDisallowedApplication: the framework's socket then goes direct
 * while every other app's traffic still enters the TUN. Known side
 * effect: this app's own non-tunnel traffic (e.g. subscription
 * fetch) also bypasses the VPN. Proper fix (future work): plumb a
 * ProtectHandler callback into the Go dial path and drop this line.
 *
 * Per-app routing: startVpn applies PerAppPrefs (ALL/BLOCKLIST/ALLOWLIST)
 * to the Builder; see applyPerAppRouting. Our own app is excluded from
 * the tunnel in every mode. A setting change takes effect on next
 * connect; the live tunnel is not re-routed.
 *
 * Stats: the gomobile API has no StatsListener (gomobile cannot bind
 * interfaces), so this service polls getUploadBytes/getDownloadBytes
 * once a second and pushes them into [MistVpnController].
 *
 * Restart: the last node URL is kept in SharedPreferences so a
 * START_STICKY restart (null intent) can bring the tunnel back up
 * without user action. [MistVpnController.stop] clears it.
 */
class MistVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.chan.weave.mist.ACTION_START"
        const val ACTION_STOP = "com.chan.weave.mist.ACTION_STOP"
        const val EXTRA_NODE_URL = "com.chan.weave.mist.EXTRA_NODE_URL"

        const val PREFS = "mist_vpn"
        const val KEY_NODE_URL = "node_url"

        private const val TAG = "MistVpn"
        private const val NOTIF_ID = 2
        private const val CHANNEL_ID = "mist_vpn"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: MobileEngine? = null
    private var currentNode: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val nodeUrl = intent.getStringExtra(EXTRA_NODE_URL)
                if (nodeUrl.isNullOrEmpty()) {
                    Log.e(TAG, "start without node url")
                    MistVpnController.setError("missing node url")
                    stopSelf()
                } else {
                    startVpn(nodeUrl)
                }
            }
            ACTION_STOP -> stopVpn()
            null -> {
                // START_STICKY restart after the process was killed.
                val saved = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_NODE_URL, null)
                if (saved.isNullOrEmpty()) {
                    stopSelf()
                } else {
                    startVpn(saved)
                }
            }
            else -> Log.w(TAG, "unknown action: ${intent.action}")
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // User revoked VPN permission from system settings.
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopEngine()
        scope.cancel()
        super.onDestroy()
    }

    private fun startVpn(nodeUrl: String) {
        if (engine != null) {
            if (currentNode == nodeUrl) return // already on this node
            stopEngine()
        }
        MistVpnController.setState(MistVpnController.State.CONNECTING)
        try {
            val builder = Builder()
                .setSession("Mist")
                .addAddress("10.0.0.2", 32)
                .addAddress("fd00::2", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("2001:4860:4860::8888")
                .setMtu(1400)
            applyPerAppRouting(builder)
            val pfd: ParcelFileDescriptor? = builder.establish()
            if (pfd == null) {
                Log.e(TAG, "establish() returned null (permission revoked?)")
                MistVpnController.setError("vpn permission revoked")
                stopSelf()
                return
            }
            // Ownership moves to Go from here on.
            val fd = pfd.detachFd()
            // No manual loadLibrary: the gomobile runtime (go.Seq) loads
            // "gojni" itself on first use, and Mist-only builds ship only
            // libgojni.so.
            val eng = MobileEngine()
            try {
                // Go int maps to Java long in the gomobile binding.
                eng.start(fd.toLong(), nodeUrl)
            } catch (e: Exception) {
                // Go error return surfaces as a Java exception.
                Log.e(TAG, "engine start failed", e)
                MistVpnController.setError(e.message ?: "engine start failed")
                stopSelf()
                return
            }
            engine = eng
            MistVpnController.engine = eng
            currentNode = nodeUrl
            goForeground(nodeUrl)
            MistVpnController.setState(MistVpnController.State.CONNECTED)
            Log.i(TAG, "vpn started node=$nodeUrl fd=$fd")
            // Poll traffic stats (no callback API in the gomobile binding).
            scope.launch {
                while (isActive) {
                    try {
                        val e = engine
                        if (e != null && e.isRunning) {
                            MistVpnController.updateStats(
                                e.uploadBytes,
                                e.downloadBytes
                            )
                            // Java getters map to Kotlin properties.
                            MistVpnController.updateDiagnostics(
                                e.tunWriteErrors,
                                e.acksSent,
                                e.ackSendErrors,
                                e.datagramsSent,
                                e.datagramsSendErrors,
                                e.datagramsReceived
                            )
                        }
                    } catch (_: Throwable) {
                    }
                    delay(1000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startVpn failed", e)
            MistVpnController.setError(e.message ?: "start failed")
            stopSelf()
        }
    }

    /**
     * Applies the user's per-app routing preference (PerAppPrefs, SharedPreferences
     * file "per_app") to the Builder. Takes effect on next connect; changing the
     * setting while connected does not re-route the live tunnel.
     *
     * Android allows only ONE of the two lists per Builder: calling both
     * addAllowedApplication and addDisallowedApplication throws
     * UnsupportedOperationException, so each mode below uses exactly one kind.
     *
     * Weave itself never enters the tunnel in any mode: its own UDP socket to
     * the Mist server would be routed back into the TUN and loop (see class
     * KDoc). In BLOCKLIST/ALL this is a disallow entry; in ALLOWLIST it is
     * achieved by never adding our own package to the allow list (only listed
     * apps enter the TUN, everything else bypasses it).
     *
     * Edge: an ALLOWLIST whose effective list is empty (user picked nothing,
     * or only our own package, or every entry failed to resolve) would mean
     * "no restriction" on Android, which would route our own socket into the
     * TUN. Fall back to disallowing only ourselves (global tunnel) instead.
     * Unresolvable package names (app uninstalled) are skipped with a log line
     * so one stale entry cannot kill the whole tunnel start.
     */
    private fun applyPerAppRouting(builder: VpnService.Builder) {
        val mode = PerAppPrefs.getMode(this)
        val packages = PerAppPrefs.getPackages(this)
        when (mode) {
            PerAppPrefs.Mode.BLOCKLIST -> {
                addDisallowedApp(builder, packageName)
                for (pkg in packages) {
                    if (pkg != packageName) addDisallowedApp(builder, pkg)
                }
                Log.i(TAG, "per-app BLOCKLIST: ${packages.size} entries")
            }
            PerAppPrefs.Mode.ALLOWLIST -> {
                var added = 0
                for (pkg in packages) {
                    if (pkg == packageName) continue // never route ourselves in
                    if (addAllowedApp(builder, pkg)) added++
                }
                if (added == 0) {
                    // No effective allow list: Android would treat this as
                    // unrestricted and loop our own socket. Exclude only
                    // ourselves instead (no mixing: no allowed call succeeded).
                    addDisallowedApp(builder, packageName)
                    Log.i(TAG, "per-app ALLOWLIST empty, fell back to global tunnel")
                } else {
                    Log.i(TAG, "per-app ALLOWLIST: $added apps")
                }
            }
            PerAppPrefs.Mode.ALL -> {
                addDisallowedApp(builder, packageName)
            }
        }
    }

    /** Returns true if the app was added; false if it is not installed. */
    private fun addAllowedApp(builder: VpnService.Builder, pkg: String): Boolean {
        return try {
            builder.addAllowedApplication(pkg)
            true
        } catch (e: Exception) {
            Log.w(TAG, "per-app allowlist: skipped unresolvable $pkg", e)
            false
        }
    }

    private fun addDisallowedApp(builder: VpnService.Builder, pkg: String) {
        try {
            builder.addDisallowedApplication(pkg)
        } catch (e: Exception) {
            Log.w(TAG, "per-app blocklist: skipped unresolvable $pkg", e)
        }
    }

    private fun stopEngine() {
        try {
            engine?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "engine stop", e)
        }
        engine = null
        MistVpnController.engine = null
        currentNode = null
        // The TUN fd was detached to Go and closed by engine.stop().
    }

    private fun stopVpn() {
        stopEngine()
        MistVpnController.setState(MistVpnController.State.DISCONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun goForeground(nodeUrl: String) {
        val notification = buildNotification(nodeUrl)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, 0)
        }
    }

    private fun buildNotification(nodeUrl: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Mist VPN",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Mist tunnel status" }
                )
            }
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Mist 已连接")
            .setContentText(nodeUrl.substringBefore("#"))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }
}
