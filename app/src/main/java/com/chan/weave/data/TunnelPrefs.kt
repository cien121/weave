package com.chan.weave.data

import android.content.Context
import androidx.core.content.edit

/**
 * Tunnel-level engine preferences (SharedPreferences, same convention
 * as [PerAppPrefs] / [DnsPrefs]; no DataStore in this repo).
 *
 * - split: domestic traffic direct, foreign traffic via the tunnel.
 * - adblock: DNS-layer ad-domain blocking.
 * Both default ON; MistVpnService applies them at start, and the
 * Settings toggles apply them to the live engine without reconnect.
 */
object TunnelPrefs {

    private const val FILE = "tunnel"
    private const val KEY_SPLIT_ENABLED = "split_enabled"
    private const val KEY_ADBLOCK_ENABLED = "adblock_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isSplitEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPLIT_ENABLED, true)

    fun setSplitEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_SPLIT_ENABLED, enabled) }
    }

    fun isAdblockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ADBLOCK_ENABLED, true)

    fun setAdblockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ADBLOCK_ENABLED, enabled) }
    }
}
