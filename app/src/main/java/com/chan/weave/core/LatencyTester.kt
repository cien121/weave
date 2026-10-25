package com.chan.weave.core

import android.content.Context
import com.chan.weave.data.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mobile.Mobile

/**
 * Latency testing (Mist-only).
 *
 * Mist servers listen on UDP only, so a TCP connect to the Mist port
 * always fails and can never measure latency. The real measurement is
 * a Mist handshake round-trip: [mistPing] runs one QUIC Initial +
 * AUTH exchange through the Go framework (mobile.Mobile.ping) and
 * returns its time. No TCP fallback: a failed handshake is -1.
 */
object LatencyTester {

    /** Mist handshake latency in ms, -1 on failure/timeout. */
    suspend fun mistPing(nodeUrl: String, timeoutSec: Int = 5): Long =
        withContext(Dispatchers.IO) {
            try {
                // Go int maps to Java long in the gomobile binding;
                // a Go error surfaces as a Java exception.
                Mobile.ping(nodeUrl, timeoutSec.toLong())
            } catch (_: Throwable) {
                -1L
            }
        }

    /**
     * Real-connection test. Mist-only: one handshake round-trip.
     * Kept under the old name so callers don't change.
     */
    suspend fun realTest(context: Context, node: Node): Long =
        mistPing(node.rawLink)

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
                    val ms = mistPing(node.rawLink)
                    onResult(node, ms)
                }
            }
        }.awaitAll()
    }
}
