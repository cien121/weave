package com.chan.weave.ui.screens

import com.chan.weave.BuildConfig

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chan.weave.mist.MistVpnController
import com.chan.weave.ui.components.AuroraBackground
import com.chan.weave.ui.theme.AuroraCyan
import com.chan.weave.ui.theme.InkPrimary
import com.chan.weave.ui.theme.InkSecondary
import com.chan.weave.ui.theme.NightOutline
import com.chan.weave.ui.theme.NightSurface

/**
 * Settings — quiet control room.
 *
 * Mist-only since 1.0.30: Xray-specific items are gone —
 * Mux / Fragment (Xray stream settings), Geo resources and routing
 * rules (Xray routing), chain proxy (Xray outbounds). What remains
 * is TUN-level or app-level: IPv6, auto-connect, per-app, DNS.
 */
@Composable
fun SettingsScreen(onNavigate: (String) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var ipv6 by remember { mutableStateOf(com.chan.weave.data.DnsPrefs.run { context.ipv6Enabled }) }
    var autoConnect by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 112.dp)
        ) {
            Text(
                text = "设置",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = InkPrimary,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(16.dp))

            SettingsGroup(title = "网络") {
                SettingsToggle(
                    title = "IPv6",
                    subtitle = "为 VPN 接口启用 IPv6",
                    checked = ipv6,
                    onCheckedChange = {
                        ipv6 = it
                        com.chan.weave.data.DnsPrefs.run { context.ipv6Enabled = it }
                    }
                )
                SettingsToggle(
                    title = "开机自动连接",
                    subtitle = "设备启动后自动建立隧道",
                    checked = autoConnect,
                    onCheckedChange = { autoConnect = it }
                )
            }

            SettingsGroup(title = "分流") {
                SettingsRow(
                    title = "分应用",
                    value = "按应用控制代理",
                    onClick = { onNavigate("apps") }
                )
                SettingsRow(
                    title = "DNS 与网络",
                    value = "DNS / 测速 / VPN 参数",
                    onClick = { onNavigate("dns") }
                )
            }

            SettingsGroup(title = "核心") {
                SettingsRow(
                    title = "Mist 核心版本",
                    value = MistVpnController.MIST_CORE_VERSION,
                    onClick = {}
                )
            }

            SettingsGroup(title = "关于") {
                SettingsRow(
                    title = "版本",
                    value = BuildConfig.VERSION_NAME,
                    onClick = {}
                )
                SettingsRow(title = "开源许可", value = "GPL-3.0", onClick = {})
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = InkSecondary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(NightSurface.copy(alpha = 0.85f))
                .border(1.dp, NightOutline, RoundedCornerShape(20.dp))
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = InkPrimary)
            Text(text = subtitle, fontSize = 12.sp, color = InkSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = AuroraCyan)
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    icon: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = InkPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, fontSize = 13.sp, color = InkSecondary)
        Spacer(Modifier.width(4.dp))
        Icon(icon, contentDescription = null, tint = InkSecondary)
    }
}
