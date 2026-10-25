package com.chan.weave.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chan.weave.data.Node
import com.chan.weave.ui.theme.AuroraCyan
import com.chan.weave.ui.theme.AuroraGradient
import com.chan.weave.ui.theme.AuroraMagenta
import com.chan.weave.ui.theme.AuroraViolet
import com.chan.weave.ui.theme.InkPrimary
import com.chan.weave.ui.theme.InkSecondary
import com.chan.weave.ui.theme.NightOutline
import com.chan.weave.ui.theme.NightSurface
import com.chan.weave.ui.theme.latencyColor
import com.chan.weave.core.CoreType

/**
 * Node deck — a swipeable hand of "signal cards", like a playlist.
 * No boring rows: each card is a mini instrument panel.
 */
@Composable
fun NodeDeck(
    nodes: List<Node>,
    selectedId: Long?,
    onSelect: (Node) -> Unit,
    modifier: Modifier = Modifier
) {
    if (nodes.isEmpty()) {
        EmptyDeckHint(modifier)
        return
    }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(nodes, key = { it.id }) { node ->
            SignalCard(
                node = node,
                selected = node.id == selectedId,
                onClick = { onSelect(node) }
            )
        }
    }
}

@Composable
private fun SignalCard(
    node: Node,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 1f,
        animationSpec = tween(300),
        label = "cardScale"
    )
    val borderBrush = if (selected) AuroraGradient
    else Brush.linearGradient(listOf(NightOutline, NightOutline))

    Box(
        modifier = Modifier
            .width(200.dp)
            .scale(scale)
            .clip(RoundedCornerShape(24.dp))
            .background(NightSurface.copy(alpha = 0.85f))
            .border(1.5.dp, borderBrush, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Column {
            // Header: protocol badge + core badge + latency
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProtocolBadge(protocol = node.protocol)
                    CoreBadge(coreType = node.coreType)
                }
                Text(
                    text = if (node.latencyMs < 0) "--" else "${node.latencyMs}ms",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = latencyColor(node.latencyMs)
                )
            }
            Spacer(Modifier.height(10.dp))
            // Name
            Text(
                text = node.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = InkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (node.protocol == "proxychain") {
                    // Show chain members: "xhttp → 家宽"
                    node.sni.ifBlank { "链式代理" }
                } else if (node.host.isNotBlank()) {
                    "${node.host}:${node.port}"
                } else "未配置",
                fontSize = 12.sp,
                color = InkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            LatencyBar(latencyMs = node.latencyMs)
            if (selected) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(6.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(AuroraCyan)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "当前使用",
                        fontSize = 11.sp,
                        color = AuroraCyan,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtocolBadge(protocol: String) {
    val label = when (protocol.lowercase()) {
        "proxychain" -> "PROXYCHAIN"
        else -> protocol.ifBlank { "?" }.uppercase().take(8)
    }
    val color = when (protocol.lowercase()) {
        "proxychain" -> Color(0xFFFF9800) // orange, like ng
        "mist" -> AuroraMagenta // light champagne gold, fits Obsidian Gold theme
        else -> AuroraViolet
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

/** Tiny core-engine badge: M = Mist. */
@Composable
fun CoreBadge(coreType: CoreType, modifier: Modifier = Modifier) {
    // Mist-only.
    val (label, color) = when (coreType) {
        CoreType.MIST -> "M" to AuroraCyan
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            color = color
        )
    }
}

@Composable
private fun EmptyDeckHint(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, NightOutline, RoundedCornerShape(24.dp))
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "还没有节点 — 去节点页添加第一个吧",
            fontSize = 14.sp,
            color = InkSecondary
        )
    }
}
