package com.chan.weave.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chan.weave.ui.ConnectionState
import com.chan.weave.ui.MainViewModel
import com.chan.weave.ui.components.AuroraBackground
import com.chan.weave.ui.components.ConnectionOrb
import com.chan.weave.ui.components.HudStats
import com.chan.weave.ui.components.NodeDeck
import com.chan.weave.ui.theme.InkPrimary
import com.chan.weave.ui.theme.InkSecondary
import com.chan.weave.ui.theme.AuroraCyan
import com.chan.weave.ui.theme.SignalError
import com.chan.weave.ui.theme.SignalUp
import com.chan.weave.ui.theme.SignalWarn
import kotlinx.coroutines.delay

/**
 * Home — the cockpit.
 *
 * Aurora sky, living connection orb in the center, HUD stats floating
 * beneath, and the node deck at the bottom. One tap on the orb is the
 * entire connection UX. State flows from [MainViewModel], which mirrors
 * the real [MistVpnController] tunnel state.
 */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    requestVpnPermission: (Intent) -> Unit
) {
    val state by vm.connectionState.collectAsState()
    val nodeList by vm.nodes.collectAsState()
    val currentSelectedId by vm.selectedId.collectAsState()
    val errorMsg by vm.connectionError.collectAsState()
    val notice by vm.notice.collectAsState()
    val egressIp by vm.egressIp.collectAsState()
    val trafficSpeed by vm.trafficSpeed.collectAsState()
    val diagnostics by vm.diagnostics.collectAsState()

    val selectedNode = nodeList.firstOrNull { it.id == currentSelectedId }

    // Auto-dismiss transient notices.
    if (notice != null) {
        LaunchedEffect(notice) {
            delay(2600)
            vm.consumeNotice()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 12.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Brand + status line
            Text(
                text = "Weave",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = InkPrimary,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(4.dp))
            StatusLine(state = state, errorMsg = errorMsg, notice = notice)
            // Egress IP, auto-detected v4/v6. Tap to refresh manually.
            if (state == ConnectionState.CONNECTED) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { vm.refreshEgressManually() })
                        .padding(horizontal = 32.dp)
                ) {
                    Text(
                        text = if (egressIp != null) {
                            "出口 ${egressIp!!.ip} · ${if (egressIp!!.isV6) "IPv6" else "IPv4"}"
                        } else {
                            "出口检测中…"
                        },
                        fontSize = 12.sp,
                        color = InkSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "🔄",
                        fontSize = 12.sp,
                        color = InkSecondary
                    )
                }
            }
            Spacer(Modifier.height(28.dp))

            // The orb
            ConnectionOrb(
                state = state,
                onToggle = { vm.onOrbClick(requestVpnPermission) }
            )
            Spacer(Modifier.height(24.dp))

            // HUD stats
            HudStats(
                latencyMs = selectedNode?.latencyMs ?: -1,
                downBps = trafficSpeed.downBps,
                upBps = trafficSpeed.upBps,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            // Engine diagnostics (one small line, only while connected):
            // TUN write failures and datagram counters pinpoint whether
            // Mist data actually reaches apps or dies on delivery.
            if (state == ConnectionState.CONNECTED) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "诊断 TUN写失败 ${diagnostics.tunWriteErrors} · ACK ${diagnostics.acksSent}（失败 ${diagnostics.ackSendErrors}）· 数据报 收 ${diagnostics.datagramsReceived} / 发 ${diagnostics.datagramsSent}（失败 ${diagnostics.datagramsSendErrors}）",
                    fontSize = 11.sp,
                    color = InkSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
            Spacer(Modifier.height(28.dp))

            // Node deck
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "信号源",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = InkSecondary
                    )
                    TextButton(onClick = { vm.autoSelectFastest() }) {
                        Text(
                            text = "⚡ 自动选择",
                            fontSize = 12.sp,
                            color = AuroraCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                NodeDeck(
                    nodes = nodeList,
                    selectedId = currentSelectedId,
                    onSelect = { vm.selectNode(it) }
                )
            }
        }
    }
}

@Composable
private fun StatusLine(
    state: ConnectionState,
    errorMsg: String?,
    notice: String?
) {
    val (text, color) = when {
        notice != null -> notice to SignalWarn
        state == ConnectionState.CONNECTED -> "已连接 · 隧道开启" to SignalUp
        state == ConnectionState.CONNECTING -> "正在建立隧道…" to SignalWarn
        state == ConnectionState.ERROR -> "连接失败：${errorMsg ?: "未知错误"}" to SignalError
        else -> "轻触核心开始连接" to InkSecondary
    }
    Text(
        text = text,
        fontSize = 13.sp,
        color = color,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 32.dp)
    )
}

