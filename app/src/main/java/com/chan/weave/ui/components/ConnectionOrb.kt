package com.chan.weave.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.chan.weave.ui.ConnectionState
import com.chan.weave.ui.theme.AuroraCyan
import com.chan.weave.ui.theme.AuroraGradient
import com.chan.weave.ui.theme.AuroraMagenta
import com.chan.weave.ui.theme.AuroraViolet
import com.chan.weave.ui.theme.InkPrimary
import com.chan.weave.ui.theme.NightSurface
import kotlin.math.cos
import kotlin.math.sin

/**
 * The hero of CianCore — a living energy core.
 *
 * IDLE:       dim core, slow breathing halo.
 * CONNECTING: rotating arc sweep.
 * CONNECTED:  radiant core with expanding pulse rings (heartbeat).
 *
 * Tap to toggle. This is the entire connection UX — no buttons elsewhere.
 */
@Composable
fun ConnectionOrb(
    state: ConnectionState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glowAlpha by animateFloatAsState(
        targetValue = when (state) {
            ConnectionState.CONNECTED -> 1f
            ConnectionState.CONNECTING -> 0.55f
            ConnectionState.IDLE -> 0.18f
            ConnectionState.ERROR -> 0.3f
        },
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "glow"
    )
    val coreScale by animateFloatAsState(
        targetValue = if (state == ConnectionState.CONNECTED) 1.06f else 1f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "coreScale"
    )

    // Breathing halo (always alive)
    val breath = rememberInfiniteTransition(label = "breath")
    val breathScale by breath.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "breathScale"
    )

    // Spin for CONNECTING
    val spin = rememberInfiniteTransition(label = "spin")
    val sweepAngle by spin.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "sweep"
    )

    // Heartbeat rings for CONNECTED
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring1 by pulse.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "ring1"
    )
    val ring2 by pulse.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2200, easing = LinearEasing, delayMillis = 1100)
        ),
        label = "ring2"
    )

    Box(
        modifier = modifier.size(280.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer glow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(breathScale)
                .blur(48.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AuroraViolet.copy(alpha = 0.55f * glowAlpha),
                            AuroraCyan.copy(alpha = 0.25f * glowAlpha),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Pulse rings (connected only)
        if (state == ConnectionState.CONNECTED) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                listOf(ring1, ring2).forEach { p ->
                    val radius = size.minDimension / 2 * (0.42f + 0.22f * p)
                    drawCircle(
                        color = AuroraCyan.copy(alpha = 0.5f * (1f - p)),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }

        // Connecting sweep arc
        if (state == ConnectionState.CONNECTING) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2 * 0.46f
                drawArc(
                    brush = AuroraGradient,
                    startAngle = sweepAngle,
                    sweepAngle = 120f,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(
                        (size.width - radius * 2) / 2,
                        (size.height - radius * 2) / 2
                    ),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
                )
            }
        }

        // Core button
        val interaction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(168.dp)
                .scale(coreScale)
                .clip(CircleShape)
                .background(
                    when (state) {
                        ConnectionState.CONNECTED -> AuroraGradient
                        else -> Brush.radialGradient(
                            colors = listOf(
                                NightSurface,
                                Color(0xFF0E1420)
                            )
                        )
                    }
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onToggle
                ),
            contentAlignment = Alignment.Center
        ) {
            // Inner ring detail
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2 * 0.78f
                drawCircle(
                    color = if (state == ConnectionState.CONNECTED)
                        Color.White.copy(alpha = 0.35f)
                    else
                        AuroraViolet.copy(alpha = 0.35f * (0.4f + glowAlpha)),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
            Icon(
                imageVector = if (state == ConnectionState.IDLE || state == ConnectionState.ERROR) Icons.Filled.Bolt else Icons.Filled.Close,
                contentDescription = if (state == ConnectionState.IDLE || state == ConnectionState.ERROR) "连接" else "断开",
                tint = if (state == ConnectionState.CONNECTED) Color.White else InkPrimary,
                modifier = Modifier.size(56.dp)
            )
        }

        // Orbiting particles (connected) — tiny dots circling the core
        if (state == ConnectionState.CONNECTED) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val orbitR = size.minDimension / 2 * 0.58f
                val t = (ring1 * 360f) * (Math.PI / 180f)
                for (i in 0 until 3) {
                    val a = t + i * (2 * Math.PI / 3)
                    drawCircle(
                        color = listOf(AuroraCyan, AuroraViolet, AuroraMagenta)[i]
                            .copy(alpha = 0.9f),
                        radius = 5.dp.toPx(),
                        center = Offset(
                            center.x + (orbitR * cos(a)).toFloat(),
                            center.y + (orbitR * sin(a)).toFloat()
                        )
                    )
                }
            }
        }
    }
}
