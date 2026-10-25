package com.chan.weave.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chan.weave.data.Node
import com.chan.weave.data.NodeLinkGenerator
import com.chan.weave.data.NodeLinkParser
import com.chan.weave.data.SubscriptionManager
import com.chan.weave.ui.MainViewModel
import com.chan.weave.ui.components.AuroraBackground
import com.chan.weave.ui.components.LatencyBar
import com.chan.weave.ui.theme.AuroraCyan
import com.chan.weave.ui.theme.AuroraGradient
import com.chan.weave.ui.theme.InkPrimary
import com.chan.weave.ui.theme.InkSecondary
import com.chan.weave.ui.theme.NightOutline
import com.chan.weave.ui.theme.NightSurface
import com.chan.weave.ui.theme.SignalError
import com.chan.weave.ui.theme.latencyColor

/**
 * Nodes — signal grid.
 *
 * Two-column mosaic of node tiles with latency glow instead of a
 * spreadsheet. Tap selects, long-press deletes, FAB pastes a share link.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodesScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val nodeList by vm.nodes.collectAsState()
    val testingIds by vm.testingIds.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Node?>(null) }
    var nodeAction by remember { mutableStateOf<Node?>(null) }
    var editingNode by remember { mutableStateOf<Node?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 12.dp, bottom = 96.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "节点",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    color = InkPrimary
                )
                if (nodeList.isNotEmpty()) {
                    val testing = testingIds.isNotEmpty()
                    val ctx = LocalContext.current
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Export all to clipboard
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, AuroraCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .combinedClickable(onClick = {
                                    val text = NodeLinkGenerator.exportAll(nodeList)
                                    if (text.isNotBlank()) {
                                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("nodes", text))
                                        Toast.makeText(ctx, "已复制 ${text.lines().size} 个节点链接", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(ctx, "没有可导出的节点", Toast.LENGTH_SHORT).show()
                                    }
                                })
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "📋 导出",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AuroraCyan
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, AuroraCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .combinedClickable(onClick = { vm.testAllNodes() })
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (testing) "测速中…" else "⚡ 全部测速",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AuroraCyan
                            )
                        }
                    }
                }
            }
            Text(
                text = "${nodeList.size} 个信号源 · 长按管理 · 点延迟测速",
                fontSize = 13.sp,
                color = InkSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )

            if (nodeList.isEmpty()) {
                EmptyNodesArt(Modifier.weight(1f))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 12.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(nodeList, key = { it.id }) { node ->
                        NodeTile(
                            node = node,
                            testing = testingIds.contains(node.id),
                            onClick = { vm.selectNode(node) },
                            onLongClick = { nodeAction = node },
                            onTest = { vm.testRealConnection(node) }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 112.dp),
            containerColor = AuroraCyan,
            contentColor = Color(0xFF06231F)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "添加节点")
        }
    }

    if (showAdd) {
        AddNodeDialog(
            onDismiss = { showAdd = false },
            onAdd = { link, onResult ->
                vm.addNodeFromLink(link) { err ->
                    onResult(err)
                    if (err == null) showAdd = false
                }
            },
            onAddBatch = { text, onResult -> vm.addNodesFromText(text, onResult) },
            onAddSubscription = { url, onResult -> vm.addNodesFromSubscription(url, onResult) },
            onCreateManual = { protocol ->
                vm.createManualNode(protocol) { node ->
                    editingNode = node
                }
            }
        )
    }

    nodeAction?.let { node ->
        AlertDialog(
            onDismissRequest = { nodeAction = null },
            title = { Text(node.name, color = InkPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            editingNode = node
                            nodeAction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("编辑节点", color = AuroraCyan) }
                    TextButton(
                        onClick = {
                            val ok = com.chan.weave.data.NodeShortcutManager.pinNodeShortcut(
                                context, node
                            )
                            android.widget.Toast.makeText(
                                context,
                                if (ok) "已发送到桌面" else "当前系统不支持",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            nodeAction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("添加到桌面", color = InkPrimary) }
                    TextButton(
                        onClick = {
                            pendingDelete = node
                            nodeAction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("删除节点", color = SignalError) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { nodeAction = null }) {
                    Text("取消", color = InkSecondary)
                }
            },
            containerColor = NightSurface
        )
    }

    editingNode?.let { node ->
        EditNodeDialog(
            node = node,
            onDismiss = { editingNode = null },
            onSave = { updated ->
                vm.updateNode(updated)
                editingNode = null
            }
        )
    }

    pendingDelete?.let { node ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除节点", color = InkPrimary) },
            text = { Text("确定删除「${node.name}」吗？", color = InkSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteNode(node)
                    pendingDelete = null
                }) { Text("删除", color = SignalError) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = InkSecondary)
                }
            },
            containerColor = NightSurface
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeTile(
    node: Node,
    testing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTest: () -> Unit
) {
    val accent = latencyColor(node.latencyMs)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(NightSurface.copy(alpha = 0.85f))
            .border(1.dp, NightOutline, RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accent.copy(alpha = 0.16f))
                        .combinedClickable(onClick = onTest)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = when {
                            testing -> "…"
                            node.latencyMs < 0 -> "--"
                            else -> "${node.latencyMs}ms"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                }
                if (node.isSelected) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AuroraGradient)
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "使用中",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = node.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = InkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = node.protocol.uppercase().ifBlank { "未知协议" },
                fontSize = 11.sp,
                color = InkSecondary
            )
            Spacer(Modifier.height(10.dp))
            LatencyBar(latencyMs = node.latencyMs)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun AddNodeDialog(
    onDismiss: () -> Unit,
    onAdd: (String, (String?) -> Unit) -> Unit,
    onAddBatch: (String, (SubscriptionManager.ImportResult) -> Unit) -> Unit,
    onAddSubscription: (String, (Result<SubscriptionManager.ImportResult>) -> Unit) -> Unit,
    onCreateManual: (String) -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var link by remember { mutableStateOf("") }
    var subUrl by remember { mutableStateOf("") }
    var manualProtocol by remember { mutableStateOf("vless") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var doneMsg by remember { mutableStateOf<String?>(null) }

    val protocols = listOf(
        "vless" to "VLESS",
        "vmess" to "VMess",
        "trojan" to "Trojan",
        "ss" to "Shadowsocks",
        "socks" to "SOCKS",
        "http" to "HTTP",
        "hysteria2" to "Hysteria2",
        "mist" to "🌫️ Mist",
    )

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("添加节点", color = InkPrimary) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("链接", "手动", "订阅").forEachIndexed { i, label ->
                        val selected = tab == i
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) AuroraCyan.copy(alpha = 0.2f)
                                    else Color.Transparent
                                )
                                .border(
                                    1.dp,
                                    if (selected) AuroraCyan else NightOutline,
                                    RoundedCornerShape(10.dp)
                                )
                                .combinedClickable(onClick = {
                                    tab = i; error = null; doneMsg = null
                                })
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                color = if (selected) AuroraCyan else InkSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (tab == 0) {
                    Text(
                        text = "粘贴分享链接，每行一个，支持批量",
                        fontSize = 12.sp,
                        color = InkSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it; error = null; doneMsg = null },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("vless://…\nvmess://…", color = InkSecondary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        minLines = 4
                    )
                } else if (tab == 1) {
                    Text(
                        text = "选择协议，创建空白节点手动填写",
                        fontSize = 12.sp,
                        color = InkSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        protocols.forEach { (value, label) ->
                            val selected = manualProtocol == value
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) AuroraCyan.copy(alpha = 0.2f)
                                        else Color.Transparent
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) AuroraCyan else NightOutline,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .combinedClickable(onClick = { manualProtocol = value })
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 13.sp,
                                    color = if (selected) AuroraCyan else InkSecondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "输入订阅链接，自动拉取全部节点",
                        fontSize = 12.sp,
                        color = InkSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = subUrl,
                        onValueChange = { subUrl = it; error = null; doneMsg = null },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://…", color = InkSecondary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        minLines = 2
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = error!!, fontSize = 12.sp, color = SignalError)
                }
                if (doneMsg != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = doneMsg!!, fontSize = 12.sp, color = AuroraCyan)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (tab == 1) {
                        // Manual: create blank node with selected protocol
                        onCreateManual(manualProtocol)
                        onDismiss()
                        return@TextButton
                    }
                    busy = true
                    error = null
                    doneMsg = null
                    if (tab == 0) {
                        if (link.lines().count { it.trim().contains("://") } <= 1 &&
                            !link.trim().contains("\n")
                        ) {
                            onAdd(link) { err ->
                                busy = false
                                error = err
                                if (err == null) onDismiss()
                            }
                        } else {
                            onAddBatch(link) { res ->
                                busy = false
                                doneMsg = "成功导入 ${res.added} 个" +
                                        if (res.failed > 0) "，${res.failed} 个失败" else ""
                                if (res.errors.isNotEmpty()) error = res.errors.first()
                                if (res.added > 0 && res.failed == 0) onDismiss()
                            }
                        }
                    } else {
                        onAddSubscription(subUrl) { result ->
                            busy = false
                            result.fold(
                                onSuccess = { res ->
                                    doneMsg = "成功导入 ${res.added} 个" +
                                            if (res.failed > 0) "，${res.failed} 个失败" else ""
                                    if (res.errors.isNotEmpty()) error = res.errors.first()
                                    if (res.added > 0 && res.failed == 0) onDismiss()
                                },
                                onFailure = { t -> error = t.message ?: "订阅失败" }
                            )
                        }
                    }
                }
            ) { Text(if (tab == 2) "拉取" else "添加", color = AuroraCyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = InkSecondary) }
        },
        containerColor = NightSurface
    )
}

@Composable
private fun EmptyNodesArt(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "◌", fontSize = 64.sp, color = InkSecondary)
            Text(
                text = "空无一物",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = InkPrimary
            )
            Text(
                text = "点右下角 + 添加第一个节点",
                fontSize = 13.sp,
                color = InkSecondary
            )
        }
    }
}

/**
 * Full manual node editor: fixes mis-parsed imports.
 * Sectioned by protocol: basic, auth, transport, TLS, Reality,
 * WS/XHTTP, gRPC, Hysteria2. Protocol itself is fixed.
 */
@Composable
private fun EditNodeDialog(
    node: Node,
    onDismiss: () -> Unit,
    onSave: (Node) -> Unit
) {
    val proto = node.protocol.lowercase()
    val key = node.id

    var name by remember(key) { mutableStateOf(node.name) }
    var host by remember(key) { mutableStateOf(node.host) }
    var port by remember(key) { mutableStateOf(node.port.toString()) }
    var uuid by remember(key) { mutableStateOf(node.uuid) }
    var password by remember(key) { mutableStateOf(node.password) }
    var method by remember(key) { mutableStateOf(node.method) }
    var flow by remember(key) { mutableStateOf(node.flow) }
    var network by remember(key) { mutableStateOf(node.network.ifBlank { "tcp" }) }
    var security by remember(key) { mutableStateOf(node.security) }
    var sni by remember(key) { mutableStateOf(node.sni) }
    var alpn by remember(key) { mutableStateOf(node.alpn) }
    var fp by remember(key) { mutableStateOf(node.fp) }
    var pbk by remember(key) { mutableStateOf(node.pbk) }
    var sid by remember(key) { mutableStateOf(node.sid) }
    var spx by remember(key) { mutableStateOf(node.spx) }
    var insecure by remember(key) { mutableStateOf(node.insecure) }
    var wsHost by remember(key) { mutableStateOf(node.wsHost) }
    var wsPath by remember(key) { mutableStateOf(node.wsPath) }
    var serviceName by remember(key) { mutableStateOf(node.serviceName) }
    var authority by remember(key) { mutableStateOf(node.authority) }
    var headerType by remember(key) { mutableStateOf(node.headerType.ifBlank { "none" }) }
    var bandwidthUp by remember(key) { mutableStateOf(node.bandwidthUp) }
    var bandwidthDown by remember(key) { mutableStateOf(node.bandwidthDown) }
    var obfsPassword by remember(key) { mutableStateOf(node.obfsPassword) }
    var portHopping by remember(key) { mutableStateOf(node.portHopping) }
    var hopInterval by remember(key) { mutableStateOf(node.hopInterval) }
    var xhttpMode by remember(key) { mutableStateOf(node.xhttpMode) }
    var xhttpExtra by remember(key) { mutableStateOf(node.xhttpExtra) }
    var mistClientId by remember(key) { mutableStateOf(node.mistClientId) }
    var mistAuthKey by remember(key) { mutableStateOf(node.mistAuthKey) }
    var finalMaskJson by remember(key) { mutableStateOf(node.finalMaskJson) }
    var echConfigList by remember(key) { mutableStateOf(node.echConfigList) }
    var peerCertSha256 by remember(key) { mutableStateOf(node.peerCertSha256) }
    var verifyPeerCertByName by remember(key) { mutableStateOf(node.verifyPeerCertByName) }
    var coreType by remember(key) { mutableStateOf(node.coreType) }
    var error by remember(key) { mutableStateOf<String?>(null) }

    val isHy2 = proto == "hysteria2" || proto == "hy2"
    val isMist = proto == "mist"
    val isStream = proto == "vless" || proto == "vmess" || proto == "trojan"
    val showTls = security == "tls" || proto == "trojan" || isHy2
    val showReality = security == "reality"
    // Mist-only core.
    val coreOptions = listOf(com.chan.weave.core.CoreType.MIST)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑节点 · ${node.protocol.uppercase()}", color = InkPrimary) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EditSection("基础")
                EditField("备注名", name, onChange = { name = it; error = null })
                EditField("服务器地址", host, onChange = { host = it; error = null })
                EditField(
                    "端口", port,
                    onChange = { port = it.filter { c -> c.isDigit() }; error = null }
                )

                EditSection("认证")
                when (proto) {
                    "vless", "vmess" -> {
                        EditField("用户 ID (UUID)", uuid, onChange = { uuid = it; error = null })
                        EditField(
                            if (proto == "vless") "加密方式（一般 none）" else "加密方式",
                            method, onChange = { method = it }
                        )
                    }
                    "trojan" -> EditField("密码", uuid, onChange = { uuid = it; error = null })
                    "ss", "shadowsocks" -> {
                        EditField("密码", uuid, onChange = { uuid = it; error = null })
                        EditField("加密方式", method, onChange = { method = it })
                    }
                    "socks", "socks5" -> {
                        EditField("用户名（可空）", uuid, onChange = { uuid = it })
                        EditField("密码（可空）", password, onChange = { password = it })
                    }
                    "tuic" -> {
                        EditField("UUID", uuid, onChange = { uuid = it; error = null })
                        EditField("密码", password, onChange = { password = it })
                    }
                    "mist" -> {
                        EditField("Client ID（hex，32 字符）", uuid,
                            onChange = { uuid = it; error = null })
                        EditField("Auth Key（标准 base64，44 字符）", password,
                            onChange = { password = it; error = null })
                    }
                    "anytls" -> {
                        EditField("密码", password, onChange = { password = it; error = null })
                    }
                    "wireguard" -> {
                        EditField("本地私钥", uuid, onChange = { uuid = it; error = null })
                        EditField("对端公钥", password, onChange = { password = it })
                    }
                    else -> EditField("认证密码", uuid, onChange = { uuid = it; error = null })
                }

                if (isMist) {
                    EditSection("Mist 设置")
                    Text(
                        text = "传输固定 TCP+TLS，uTLS 指纹 hellochrome_155，证书校验开启",
                        fontSize = 12.sp,
                        color = InkSecondary
                    )
                    EditField("SNI（雾域名，可空，默认=服务器地址）", sni,
                        onChange = { sni = it })
                }

                if (isStream) {
                    EditSection("传输设置")
                    EditChips(
                        "传输协议",
                        listOf("tcp", "ws", "xhttp", "grpc", "httpupgrade", "kcp", "h2", "mist"),
                        network, onSelect = { network = it }
                    )
                    EditChipsPairs(
                        "传输安全",
                        listOf("" to "无", "tls" to "TLS", "reality" to "Reality"),
                        security, onSelect = { security = it }
                    )
                    if (network == "tcp" || network == "kcp") {
                        EditChips("伪装头", listOf("none", "http"), headerType,
                            onSelect = { headerType = it })
                    }
                    if (network == "ws" || network == "xhttp" || network == "httpupgrade" || network == "h2") {
                        EditField("路径", wsPath, onChange = { wsPath = it })
                        EditField("Host（可空）", wsHost, onChange = { wsHost = it })
                    }
                    if (network == "xhttp") {
                        EditChips(
                            "XHTTP 模式",
                            listOf("auto", "packet-up", "stream-up", "stream-one"),
                            xhttpMode, onSelect = { xhttpMode = it }
                        )
                        EditField("XHTTP Extra 原始 JSON（可空）", xhttpExtra,
                            onChange = { xhttpExtra = it })
                    }
                    if (network == "mist") {
                        EditField("Mist Client ID（16 字节 hex）", mistClientId,
                            onChange = { mistClientId = it })
                        EditField("Mist Auth Key（32 字节 base64）", mistAuthKey,
                            onChange = { mistAuthKey = it })
                    }
                    if (network == "grpc") {
                        EditField("服务名", serviceName, onChange = { serviceName = it })
                        EditField("Authority（可空）", authority, onChange = { authority = it })
                    }
                }

                if (showTls) {
                    EditSection("TLS 设置")
                    EditField("SNI / 服务器名", sni, onChange = { sni = it })
                    EditField("ALPN（逗号分隔，可空）", alpn, onChange = { alpn = it })
                    EditChips(
                        "uTLS 指纹",
                        listOf("", "chrome", "firefox", "safari", "ios", "android", "edge", "random"),
                        fp, onSelect = { fp = it }
                    )
                    EditField("ECH 配置（可空）", echConfigList,
                        onChange = { echConfigList = it })
                    EditField("证书指纹 SHA-256（可空）", peerCertSha256,
                        onChange = { peerCertSha256 = it })
                    EditField("按名称验证证书（可空）", verifyPeerCertByName,
                        onChange = { verifyPeerCertByName = it })
                    if (proto == "vless" || proto == "trojan") {
                        EditField("流控（可空，如 xtls-rprx-vision）", flow, onChange = { flow = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("允许不安全证书", fontSize = 14.sp, color = InkPrimary,
                            modifier = Modifier.weight(1f))
                        Switch(
                            checked = insecure, onCheckedChange = { insecure = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = AuroraCyan)
                        )
                    }
                }

                if (showReality) {
                    EditSection("Reality 设置")
                    EditField("公钥", pbk, onChange = { pbk = it })
                    EditField("ShortId", sid, onChange = { sid = it })
                    EditField("SpiderX（可空）", spx, onChange = { spx = it })
                    if (proto == "vless" || proto == "trojan") {
                        EditField("流控（可空，如 xtls-rprx-vision）", flow, onChange = { flow = it })
                    }
                }

                if (isHy2) {
                    EditSection("Hysteria2 设置")
                    EditField("上传带宽（可空，如 50 mbps）", bandwidthUp,
                        onChange = { bandwidthUp = it })
                    EditField("下载带宽（可空，如 100 mbps）", bandwidthDown,
                        onChange = { bandwidthDown = it })
                    EditField("混淆密码（可空）", obfsPassword, onChange = { obfsPassword = it })
                    EditField("端口跳跃（可空，如 20000-30000）", portHopping,
                        onChange = { portHopping = it })
                    EditField("端口跳跃间隔（秒，可空）", hopInterval,
                        onChange = { hopInterval = it.filter { c -> c.isDigit() } })
                    EditField("FinalMask 原始 JSON（可空，高级）", finalMaskJson,
                        onChange = { finalMaskJson = it })
                }

                if (isStream) {
                    EditSection("高级")
                    EditField("FinalMask 原始 JSON（可空）", finalMaskJson,
                        onChange = { finalMaskJson = it })
                }

                error?.let {
                    Text(text = it, fontSize = 12.sp, color = SignalError)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = port.toIntOrNull()
                when {
                    name.isBlank() -> error = "备注名不能为空"
                    host.isBlank() -> error = "服务器地址不能为空"
                    p == null || p !in 1..65535 -> error = "端口无效（1-65535）"
                    uuid.isBlank() && proto != "socks" && proto != "socks5" ->
                        error = "认证信息不能为空"
                    isMist && !NodeLinkParser.isValidMistClientId(uuid) ->
                        error = "Client ID 必须为 32 个 hex 字符"
                    isMist && !NodeLinkParser.isValidMistKey(password) ->
                        error = "Auth Key 无效（应为 32 字节密钥的 base64 编码）"
                    showReality && pbk.isBlank() -> error = "Reality 公钥不能为空"
                    else -> onSave(
                        node.copy(
                            name = name.trim(), host = host.trim(), port = p,
                            uuid = uuid, password = password, method = method,
                            flow = flow, network = if (isMist) "tcp" else network,
                            security = if (isMist) "tls" else security,
                            sni = sni, alpn = alpn, fp = fp, pbk = pbk, sid = sid,
                            spx = spx, insecure = insecure, wsHost = wsHost,
                            wsPath = wsPath.ifBlank { "/" }, serviceName = serviceName,
                            authority = authority, headerType = headerType,
                            bandwidthUp = bandwidthUp, bandwidthDown = bandwidthDown,
                            obfsPassword = obfsPassword, portHopping = portHopping,
                            hopInterval = hopInterval,
                            xhttpMode = xhttpMode, xhttpExtra = xhttpExtra,
                            mistClientId = mistClientId, mistAuthKey = mistAuthKey,
                            finalMaskJson = finalMaskJson,
                            echConfigList = echConfigList,
                            peerCertSha256 = peerCertSha256,
                            verifyPeerCertByName = verifyPeerCertByName,
                            coreType = coreType
                        )
                    )
                }
            }) { Text("保存", color = AuroraCyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = InkSecondary) }
        },
        containerColor = NightSurface
    )
}

@Composable
private fun EditSection(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = AuroraCyan,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
@Composable
private fun EditField(
    label: String,
    value: String,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun EditChips(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) = EditChipsPairs(label, options.map { it to it }, selected, onSelect)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun EditChipsPairs(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val all = remember(options, selected) {
        if (options.any { it.first == selected }) options
        else options + (selected to selected.ifBlank { "自定义" })
    }
    Column {
        Text(text = label, fontSize = 12.sp, color = InkSecondary)
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            all.forEach { (value, text) ->
                val isSel = value == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSel) AuroraCyan.copy(alpha = 0.85f)
                            else Color(0xFF2A2F3A)
                        )
                        .border(
                            1.dp,
                            if (isSel) AuroraCyan else NightOutline,
                            RoundedCornerShape(10.dp)
                        )
                        .combinedClickable(onClick = { onSelect(value) })
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = text.ifBlank { "无" },
                        fontSize = 12.sp,
                        color = Color.White,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

