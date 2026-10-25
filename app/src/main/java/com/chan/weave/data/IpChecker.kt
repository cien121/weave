package com.chan.weave.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mobile.MobileEngine
import org.json.JSONObject

/**
 * Egress IP check: what the internet sees through the tunnel.
 *
 * The request runs INSIDE the Mist engine: [MobileEngine.fetchEgressIP]
 * opens a Mist stream to the check URL, so the check itself traverses
 * the tunnel. This replaces the old Xray-era path that routed through
 * a local SOCKS inbound (127.0.0.1:10808), which does not exist in
 * Mist-only builds — every check was connection-refused and the UI
 * showed "检测中…" forever.
 *
 * Never "fix" this by going direct: the app's own direct HTTP traffic
 * bypasses the VPN (the app package is excluded from the TUN to avoid
 * routing loops), so a direct connection would return the real IP,
 * not the egress IP.
 */
object IpChecker {

    data class IpInfo(val ip: String, val isV6: Boolean)

    /**
     * Fetch the egress IP through the running engine. One retry on
     * failure or parse error; null if both attempts fail. Runs off
     * the main thread. A null engine means not connected.
     */
    suspend fun checkViaEngine(engine: MobileEngine?, url: String): IpInfo? =
        withContext(Dispatchers.IO) {
            val eng = engine ?: return@withContext null
            repeat(2) { attempt ->
                try {
                    // Go int maps to Java long in the gomobile binding;
                    // a Go error surfaces as a Java exception.
                    val body = eng.fetchEgressIP(url, 10L)
                    extractIp(body)?.let { ip ->
                        return@withContext IpInfo(ip, ip.contains(":"))
                    }
                } catch (_: Throwable) {
                }
                if (attempt == 0) delay(1000)
            }
            null
        }

    /** Accepts plain-text IP or JSON like {"ip":"1.2.3.4",...}. */
    fun extractIp(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed.length > 4096) return null
        if (!trimmed.startsWith("{")) {
            val ip = trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
            return ip.takeIf { it.isNotEmpty() && it.length <= 45 && '/' !in it }
        }
        return try {
            JSONObject(trimmed).optString("ip").takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }
}
