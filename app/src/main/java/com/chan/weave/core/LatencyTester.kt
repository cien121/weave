package com.chan.weave.core

import android.content.Context
import com.chan.weave.data.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Latency testing (Mist-only).
 *
 * Weave no longer ships Xray, so there is no "real-connection"
 * (Xray handshake) test. TCP connect timing is the meaningful
 * measurement for a Mist node: it proves the server address is
 * reachable. The Mist tunnel itself is verified by real traffic
 * after connecting, not by a pre-flight handshake.
 */
object LatencyTester {

    /** TCP connect latency in ms, -1 on failure/timeout. */
    suspend fun tcpPing(host: String, port: Int, timeoutMs: Int = 5000): Long =
        withContext(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
                System.currentTimeMillis() - start
            } catch (_: Throwable) {
                -1L
            }
        }

    /**
     * Real-connection test. Mist-only: same as TCP ping.
     * Kept under the old name so callers don't change.
     */
    suspend fun realTest(context: Context, node: Node): Long =
        tcpPing(node.host, node.port)

    /**
     * Test many nodes with bounded concurrency.
     * Calls [onResult] for each finished node.
     */
    suspend fun testAll(
        context: Context,
        nodes: List<Node>,
        maxConcurrent: Int,
        onResult: suspend (Node, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(maxConcurrent.coerceIn(1, 32))
        nodes.map { node ->
            async {
                semaphore.withPermit {
                    val ms = tcpPing(node.host, node.port)
                    onResult(node, ms)
                }
            }
        }.awaitAll()
    }
}
