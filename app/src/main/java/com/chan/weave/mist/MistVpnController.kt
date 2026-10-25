package com.chan.weave.mist

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import mobile.MobileEngine

/**
 * UI-facing API for the Mist VPN service. Weave only talks to this
 * object, never to the service directly (the live engine is exposed
 * here so the egress check can run inside the tunnel).
 *
 * Typical flow from a connect button (must run in the foreground —
 * Android 12+ forbids background foreground-service starts):
 *   1. if (!MistVpnController.isPrepared(ctx)) {
 *        startActivityForResult(MistVpnController.prepareIntent(ctx), REQ)
 *        // on RESULT_OK -> step 2
 *      }
 *   2. MistVpnController.start(ctx, mistNodeUrl)
 *   3. Observe MistVpnController.onStateChanged / uploadBytes /
 *      downloadBytes to render status and traffic.
 *   4. MistVpnController.stop(ctx) to disconnect.
 *
 * State and stats are plain @Volatile fields (no AndroidX
 * dependency): the UI polls them or sets onStateChanged. Stats
 * arrive from the service's 1s poll loop (the gomobile binding has
 * no callback API); onStateChanged is invoked on whatever thread
 * set the state, so post to the main thread before touching views.
 */
object MistVpnController {

    /**
     * Independent Mist framework version, shown in Settings → 核心.
     * Tracks cien121/mist-framework releases; bump when the AAR is
     * rebuilt from a new framework commit.
     */
    const val MIST_CORE_VERSION = "1.0.0"

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    @Volatile var state: State = State.DISCONNECTED
        private set

    @Volatile var errorMessage: String? = null
        private set

    @Volatile var uploadBytes: Long = 0
        private set

    @Volatile var downloadBytes: Long = 0
        private set

    /**
     * Live gomobile engine while connected, null otherwise.
     * Set by [MistVpnService]; MainViewModel uses it for the
     * engine-internal egress check (never a direct HTTP call).
     */
    @Volatile var engine: MobileEngine? = null

    /** Engine diagnostics, polled by the service once a second. */
    @Volatile var tunWriteErrors: Long = 0
        private set

    @Volatile var acksSent: Long = 0
        private set

    @Volatile var ackSendErrors: Long = 0
        private set

    @Volatile var datagramsSent: Long = 0
        private set

    @Volatile var datagramsSendErrors: Long = 0
        private set

    @Volatile var datagramsReceived: Long = 0
        private set

    /** Invoked on state change; may be called off the main thread. */
    var onStateChanged: ((State) -> Unit)? = null

    fun setState(s: State) {
        state = s
        if (s != State.ERROR) errorMessage = null
        onStateChanged?.invoke(s)
    }

    fun setError(msg: String) {
        errorMessage = msg
        setState(State.ERROR)
    }

    fun updateStats(up: Long, down: Long) {
        uploadBytes = up
        downloadBytes = down
    }

    fun updateDiagnostics(
        tunWriteErrors: Long,
        acksSent: Long,
        ackSendErrors: Long,
        datagramsSent: Long,
        datagramsSendErrors: Long,
        datagramsReceived: Long
    ) {
        this.tunWriteErrors = tunWriteErrors
        this.acksSent = acksSent
        this.ackSendErrors = ackSendErrors
        this.datagramsSent = datagramsSent
        this.datagramsSendErrors = datagramsSendErrors
        this.datagramsReceived = datagramsReceived
    }

    /** True when VPN permission is already granted. */
    fun isPrepared(context: Context): Boolean = VpnService.prepare(context) == null

    /**
     * Intent to request VPN permission, or null when already granted.
     * Launch with startActivityForResult; proceed on RESULT_OK.
     */
    fun prepareIntent(context: Context): Intent? = VpnService.prepare(context)

    /**
     * Connect with a mist:// node link, e.g.
     * mist://<cidHex>@<host>:<port>?key=<base64url>&sni=<domain>#<name>
     * Throws IllegalArgumentException for a non-mist link.
     */
    fun start(context: Context, nodeUrl: String) {
        require(nodeUrl.startsWith("mist://")) { "not a mist:// link" }
        context.getSharedPreferences(MistVpnService.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(MistVpnService.KEY_NODE_URL, nodeUrl)
            .apply()
        val intent = Intent(context, MistVpnService::class.java).apply {
            action = MistVpnService.ACTION_START
            putExtra(MistVpnService.EXTRA_NODE_URL, nodeUrl)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /** Disconnect and clear the sticky-restart node. */
    fun stop(context: Context) {
        context.getSharedPreferences(MistVpnService.PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(MistVpnService.KEY_NODE_URL)
            .apply()
        val intent = Intent(context, MistVpnService::class.java).apply {
            action = MistVpnService.ACTION_STOP
        }
        context.startService(intent)
    }
}

