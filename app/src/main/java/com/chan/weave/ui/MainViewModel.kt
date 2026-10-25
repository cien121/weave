package com.chan.weave.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chan.weave.CianCoreApp
import com.chan.weave.core.CoreRegistry
import com.chan.weave.core.LatencyTester
import com.chan.weave.data.DnsPrefs.concurrentTests
import com.chan.weave.data.IpChecker
import com.chan.weave.data.Node
import com.chan.weave.data.NodeDao
import com.chan.weave.data.NodeLinkParser
import com.chan.weave.data.NodeShortcutManager
import com.chan.weave.data.SubscriptionManager
import com.chan.weave.mist.MistVpnController
import com.chan.weave.service.CianCoreVpnService
import com.chan.weave.service.CianCoreVpnService.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home/Nodes shared view model.
 *
 * - Connection state merges [CianCoreVpnService.state] (Xray path)
 *   with the Mist framework path: when a mist:// node is active, the
 *   Mist state wins, so the orb always reflects the real tunnel state.
 * - Nodes come from Room; the selected node drives connect().
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao: NodeDao = CianCoreApp.instance.database.nodeDao()

    /** Mist-backend mirror: IDLE unless a mist:// tunnel is active. */
    private val _mistState = MutableStateFlow(ConnectionState.IDLE)
    private val _mistError = MutableStateFlow<String?>(null)
    private val _mistActiveNodeId = MutableStateFlow<Long?>(null)

    /** True while the active tunnel runs on the Mist framework. */
    private val _mistActive = MutableStateFlow(false)

    val connectionState: StateFlow<ConnectionState> = combine(
        CianCoreVpnService.state, _mistState, _mistActive
    ) { xray, mist, mistActive ->
        if (mistActive) mist else xray
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.IDLE)

    val connectionError: StateFlow<String?> = combine(
        CianCoreVpnService.errorMessage, _mistError, _mistActive
    ) { xray, mist, mistActive ->
        if (mistActive) mist else xray
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val activeNodeId: StateFlow<Long?> = combine(
        CianCoreVpnService.activeNodeId, _mistActiveNodeId, _mistActive
    ) { xray, mist, mistActive ->
        if (mistActive) mist else xray
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val nodes: StateFlow<List<Node>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _egressIp = MutableStateFlow<IpChecker.IpInfo?>(null)
    val egressIp: StateFlow<IpChecker.IpInfo?> = _egressIp.asStateFlow()

    /** Refresh egress IP. Protocol-aware: hysteria2/socks prefer v4, others v6. */
    private fun refreshEgress() {
        viewModelScope.launch {
            val nodeId = activeNodeId.value
            val preferV6 = withContext(Dispatchers.IO) {
                val node = nodeId?.let { runCatching { dao.getById(it) }.getOrNull() }
                when (node?.protocol?.lowercase()) {
                    "hysteria2", "socks" -> false
                    "proxychain" -> {
                        // For chain, use the LAST member (exit)'s protocol
                        val ids = getChainMemberIds(node)
                        val last = ids.lastOrNull()?.let { runCatching { dao.getById(it) }.getOrNull() }
                        when (last?.protocol?.lowercase()) {
                            "hysteria2", "socks" -> false
                            else -> true
                        }
                    }
                    else -> true
                }
            }
            _egressIp.value = IpChecker.check(getApplication(), preferV6)
        }
    }

    /** Manual refresh triggered by tapping the egress IP display. */
    fun refreshEgressManually() {
        if (connectionState.value == ConnectionState.CONNECTED) {
            _egressIp.value = null // show "检测中…"
            refreshEgress()
        }
    }

    /** Live traffic speed in bytes/sec, polled while connected. */
    data class TrafficSpeed(val downBps: Long = 0, val upBps: Long = 0)
    private val _trafficSpeed = MutableStateFlow(TrafficSpeed())
    val trafficSpeed: StateFlow<TrafficSpeed> = _trafficSpeed.asStateFlow()

    init {
        viewModelScope.launch {
            _selectedId.value = withContext(Dispatchers.IO) { dao.getSelected()?.id }
        }
        // Update home-screen dynamic shortcuts when nodes change.
        viewModelScope.launch {
            nodes.collect { list ->
                try {
                    NodeShortcutManager.updateDynamicShortcuts(getApplication(), list)
                } catch (_: Throwable) {
                }
            }
        }
        // Refresh egress IP whenever the tunnel connects.
        // Protocol-aware: hysteria2/socks prefer v4, others prefer v6.
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    refreshEgress()
                } else if (state == ConnectionState.IDLE) {
                    _egressIp.value = null
                }
            }
        }
        // Poll traffic stats while connected (2s cadence).
        viewModelScope.launch {
            var lastTx = 0L
            var lastRx = 0L
            var lastAt = 0L
            while (isActive) {
                if (connectionState.value == ConnectionState.CONNECTED) {
                    try {
                        val stats = withContext(Dispatchers.IO) {
                            CoreRegistry.getOrNull(
                                com.chan.weave.core.CoreType.XRAY
                            )?.queryTrafficStats()
                        }
                        val now = System.currentTimeMillis()
                        if (stats != null && lastAt > 0) {
                            val dt = (now - lastAt).coerceAtLeast(1) / 1000.0
                            val down = ((stats.rxBytes - lastRx).coerceAtLeast(0) / dt).toLong()
                            val up = ((stats.txBytes - lastTx).coerceAtLeast(0) / dt).toLong()
                            _trafficSpeed.value = TrafficSpeed(down, up)
                        }
                        if (stats != null) {
                            lastTx = stats.txBytes
                            lastRx = stats.rxBytes
                            lastAt = now
                        }
                    } catch (_: Throwable) {
                        // Keep last known speed.
                    }
                } else {
                    lastAt = 0L
                    if (_trafficSpeed.value != TrafficSpeed()) {
                        _trafficSpeed.value = TrafficSpeed()
                    }
                }
                delay(2000)
            }
        }
        // Mirror the Mist framework state into the merged flows, and
        // compute traffic speed from its polled byte counters.
        // (MistVpnController has no callback API; the service polls
        // the gomobile engine once a second.)
        MistVpnController.onStateChanged = { s ->
            _mistState.value = when (s) {
                MistVpnController.State.DISCONNECTED -> ConnectionState.IDLE
                MistVpnController.State.CONNECTING -> ConnectionState.CONNECTING
                MistVpnController.State.CONNECTED -> ConnectionState.CONNECTED
                MistVpnController.State.ERROR -> ConnectionState.ERROR
            }
            _mistError.value = MistVpnController.errorMessage
            if (s == MistVpnController.State.DISCONNECTED ||
                s == MistVpnController.State.ERROR
            ) {
                _mistActive.value = false
                _mistActiveNodeId.value = null
            }
        }
        viewModelScope.launch {
            var lastUp = 0L
            var lastDown = 0L
            var lastAt = 0L
            while (isActive) {
                if (_mistActive.value) {
                    try {
                        val up = MistVpnController.uploadBytes
                        val down = MistVpnController.downloadBytes
                        val now = System.currentTimeMillis()
                        if (lastAt > 0) {
                            val dt = (now - lastAt).coerceAtLeast(1) / 1000.0
                            _trafficSpeed.value = TrafficSpeed(
                                downBps = ((down - lastDown).coerceAtLeast(0) / dt).toLong(),
                                upBps = ((up - lastUp).coerceAtLeast(0) / dt).toLong()
                            )
                        }
                        lastUp = up
                        lastDown = down
                        lastAt = now
                    } catch (_: Throwable) {
                    }
                } else {
                    lastAt = 0L
                }
                delay(1000)
            }
        }
    }

    /** A node is a Mist-framework node when its link uses mist://. */
    private fun isMistNode(node: Node?): Boolean =
        node?.rawLink?.trim()?.startsWith("mist://") == true

    /** Pending mist:// link waiting on the VPN permission dialog. */
    private var pendingMistLink: String? = null
    private var pendingMistNodeId: Long? = null

    /** Start a mist:// tunnel. Caller must hold VPN permission. */
    private fun startMist(ctx: Application, node: Node) {
        _mistActiveNodeId.value = node.id
        _mistActive.value = true
        _mistState.value = ConnectionState.CONNECTING
        MistVpnController.start(ctx, node.rawLink.trim())
    }

    /**
     * Orb tap. When disconnected: connect the selected node.
     * mist:// nodes go through the independent Mist framework;
     * everything else keeps the existing Xray path.
     * @param requestVpnPermission invoked with the system VPN permission
     * intent when the user hasn't granted it yet.
     */
    fun onOrbClick(requestVpnPermission: (Intent) -> Unit) {
        when (connectionState.value) {
            ConnectionState.IDLE, ConnectionState.ERROR -> {
                val nodeId = _selectedId.value
                if (nodeId == null) {
                    _notice.value = "先添加并选择一个节点"
                    return
                }
                val ctx = getApplication<Application>()
                val node = nodes.value.firstOrNull { it.id == nodeId }
                if (isMistNode(node)) {
                    val permissionIntent = MistVpnController.prepareIntent(ctx)
                    if (permissionIntent != null) {
                        pendingMistLink = node.rawLink.trim()
                        pendingMistNodeId = node.id
                        requestVpnPermission(permissionIntent)
                    } else {
                        startMist(ctx, node)
                    }
                    return
                }
                val permissionIntent = CianCoreVpnService.prepareIntent(ctx)
                if (permissionIntent != null) {
                    requestVpnPermission(permissionIntent)
                } else {
                    CianCoreVpnService.startConnect(ctx, nodeId)
                }
            }
            ConnectionState.CONNECTING -> { /* tap does nothing mid-flight */ }
            ConnectionState.CONNECTED -> {
                if (_mistActive.value) {
                    _mistActive.value = false
                    _mistActiveNodeId.value = null
                    MistVpnController.stop(getApplication())
                } else {
                    CianCoreVpnService.startDisconnect(getApplication())
                }
            }
        }
    }

    /** Called after the user grants VPN permission from the system dialog. */
    fun onVpnPermissionGranted() {
        val pendingLink = pendingMistLink
        val pendingId = pendingMistNodeId
        if (pendingLink != null && pendingId != null) {
            pendingMistLink = null
            pendingMistNodeId = null
            val ctx = getApplication<Application>()
            val node = nodes.value.firstOrNull { it.id == pendingId }
            if (node != null && isMistNode(node)) {
                startMist(ctx, node)
                return
            }
        }
        val nodeId = _selectedId.value ?: return
        if (connectionState.value == ConnectionState.IDLE ||
            connectionState.value == ConnectionState.ERROR
        ) {
            val node = nodes.value.firstOrNull { it.id == nodeId }
            if (isMistNode(node)) {
                val ctx = getApplication<Application>()
                startMist(ctx, node!!)
            } else {
                CianCoreVpnService.startConnect(getApplication(), nodeId)
            }
        }
    }

    fun selectNode(node: Node) {
        _selectedId.value = node.id
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearSelection()
            dao.selectById(node.id)
        }
    }

    /**
     * Connect directly to a node by ID (from home screen shortcut).
     * Selects the node, then connects if VPN permission is granted.
     * @param requestVpnPermission invoked with the system VPN permission
     * intent when the user hasn't granted it yet.
     */
    fun connectToNode(nodeId: Long, requestVpnPermission: (Intent) -> Unit) {
        viewModelScope.launch {
            val node = withContext(Dispatchers.IO) { dao.getById(nodeId) } ?: return@launch
            // Select first
            _selectedId.value = node.id
            withContext(Dispatchers.IO) {
                dao.clearSelection()
                dao.selectById(node.id)
            }
            // Then connect (or request permission)
            val ctx = getApplication<Application>()
            when (connectionState.value) {
                ConnectionState.IDLE, ConnectionState.ERROR -> {
                    if (isMistNode(node)) {
                        val permissionIntent = MistVpnController.prepareIntent(ctx)
                        if (permissionIntent != null) {
                            pendingMistLink = node.rawLink.trim()
                            pendingMistNodeId = node.id
                            requestVpnPermission(permissionIntent)
                        } else {
                            startMist(ctx, node)
                        }
                        return@launch
                    }
                    val permissionIntent = CianCoreVpnService.prepareIntent(ctx)
                    if (permissionIntent != null) {
                        // Need permission: store pending node and request
                        _pendingShortcutNodeId.value = node.id
                        requestVpnPermission(permissionIntent)
                    } else {
                        CianCoreVpnService.startConnect(ctx, node.id)
                    }
                }
                ConnectionState.CONNECTED -> {
                    if (isMistNode(node)) {
                        // Cross-backend switch: tear down the current
                        // tunnel first, then start the Mist tunnel.
                        if (_mistActive.value) {
                            _mistActive.value = false
                            _mistActiveNodeId.value = null
                            MistVpnController.stop(ctx)
                        } else {
                            CianCoreVpnService.startDisconnect(ctx)
                        }
                        delay(500)
                        startMist(ctx, node)
                    } else {
                        if (_mistActive.value) {
                            // Mist -> Xray switch: stop Mist first.
                            _mistActive.value = false
                            _mistActiveNodeId.value = null
                            MistVpnController.stop(ctx)
                            delay(500)
                        }
                        // Already connected: switch immediately to this node.
                        CianCoreVpnService.startSwitch(ctx, node.id)
                    }
                }
                else -> { /* connecting, ignore */ }
            }
        }
    }

    /** Pending node ID from shortcut when VPN permission was requested. */
    private val _pendingShortcutNodeId = MutableStateFlow<Long?>(null)

    /** Called after VPN permission granted for a shortcut-initiated connection. */
    fun onShortcutVpnPermissionGranted() {
        val pendingLink = pendingMistLink
        val pendingId = pendingMistNodeId
        if (pendingLink != null && pendingId != null) {
            pendingMistLink = null
            pendingMistNodeId = null
            val ctx = getApplication<Application>()
            viewModelScope.launch {
                val node = withContext(Dispatchers.IO) { dao.getById(pendingId) }
                if (node != null && isMistNode(node)) {
                    startMist(ctx, node)
                    return@launch
                }
                if (node != null) {
                    CianCoreVpnService.startConnect(ctx, node.id)
                }
            }
            return
        }
        val nodeId = _pendingShortcutNodeId.value ?: return
        _pendingShortcutNodeId.value = null
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val node = withContext(Dispatchers.IO) { dao.getById(nodeId) }
            if (node != null && isMistNode(node)) {
                startMist(ctx, node)
            } else {
                CianCoreVpnService.startConnect(ctx, nodeId)
            }
        }
    }

    /** Parse a share link and store it. Returns the error message, or null on success. */
    fun addNodeFromLink(link: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { NodeLinkParser.parse(link) }
            result.fold(
                onSuccess = { node ->
                    val id = withContext(Dispatchers.IO) { dao.insert(node) }
                    // Auto-select the first node ever added.
                    val select = withContext(Dispatchers.IO) { dao.getAll() }.size == 1
                    if (select) {
                        _selectedId.value = id
                        withContext(Dispatchers.IO) {
                            dao.clearSelection()
                            dao.selectById(id)
                        }
                    }
                    onDone(null)
                },
                onFailure = { t ->
                    onDone(t.message ?: "链接解析失败")
                }
            )
        }
    }

    fun deleteNode(node: Node) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_selectedId.value == node.id) _selectedId.value = null
            dao.delete(node)
        }
    }

    /** Test state per node id: null = idle, -2 = testing. */
    private val _testingIds = MutableStateFlow<Set<Long>>(emptySet())
    val testingIds: StateFlow<Set<Long>> = _testingIds.asStateFlow()

    /** TCP ping test for one node; stores result in DB. */
    fun testTcpPing(node: Node) {
        if (_testingIds.value.contains(node.id)) return
        _testingIds.value = _testingIds.value + node.id
        viewModelScope.launch {
            val ms = LatencyTester.tcpPing(node.host, node.port)
            withContext(Dispatchers.IO) { dao.updateLatency(node.id, ms) }
            _testingIds.value = _testingIds.value - node.id
        }
    }

    /** Real-connection test (full Xray handshake) for one node. */
    fun testRealConnection(node: Node) {
        if (_testingIds.value.contains(node.id)) return
        _testingIds.value = _testingIds.value + node.id
        viewModelScope.launch {
            try {
                val ms = LatencyTester.realTest(getApplication(), node)
                // Fall back to TCP ping if the real test fails outright.
                val final = if (ms < 0) {
                    try {
                        LatencyTester.tcpPing(node.host, node.port)
                    } catch (_: Throwable) {
                        -1L
                    }
                } else ms
                withContext(Dispatchers.IO) { dao.updateLatency(node.id, final) }
            } catch (_: Throwable) {
                // Never crash the app from a latency test
            } finally {
                _testingIds.value = _testingIds.value - node.id
            }
        }
    }

    /** Persist edited node fields. */
    fun updateNode(node: Node) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.update(node) }
        }
    }

    /** Create a blank node for manual editing. Returns the inserted node. */
    fun createManualNode(protocol: String, onDone: (Node) -> Unit) {
        viewModelScope.launch {
            // Single-core (Xray only); sing-box removed.
            val coreType = com.chan.weave.core.CoreType.XRAY
            val node = Node(
                name = "新节点",
                rawLink = "",
                protocol = protocol,
                network = "tcp",
                coreType = coreType
            )
            val id = withContext(Dispatchers.IO) { dao.insert(node) }
            onDone(node.copy(id = id))
        }
    }

    /**
     * Create a PROXYCHAIN virtual node. The chain members' IDs are stored in
     * rawLink as "proxychain:<id1>,<id2>,...". The node appears in the panel
     * like a normal node and can be connected, tested, shared, deleted.
     */
    fun createProxyChainNode(members: List<Node>) {
        if (members.isEmpty()) return
        viewModelScope.launch {
            val ids = members.map { it.id }.joinToString(",")
            val chainDesc = members.joinToString("→") { it.name }
            val node = Node(
                name = "链式",
                rawLink = "proxychain:$ids",
                protocol = "proxychain",
                network = "tcp",
                coreType = com.chan.weave.core.CoreType.XRAY,
                // Store display hint in sni field (reused)
                sni = chainDesc
            )
            withContext(Dispatchers.IO) { dao.insert(node) }
            _notice.value = "已创建链式节点：$chainDesc"
        }
    }

    /** Parse chain member IDs from a proxychain node's rawLink. */
    fun getChainMemberIds(node: Node): List<Long> {
        if (node.protocol != "proxychain") return emptyList()
        val raw = node.rawLink.removePrefix("proxychain:").trim()
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").mapNotNull { it.toLongOrNull() }
    }

    /** Test all nodes with the configured concurrency. */
    fun testAllNodes() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val list = withContext(Dispatchers.IO) { dao.getAll() }
            val pending = list.filter { !_testingIds.value.contains(it.id) }
            _testingIds.value = _testingIds.value + pending.map { it.id }
            try {
                LatencyTester.testAll(
                    ctx,
                    pending,
                    ctx.concurrentTests
                ) { node, ms ->
                    withContext(Dispatchers.IO) { dao.updateLatency(node.id, ms) }
                    _testingIds.value = _testingIds.value - node.id
                }
            } finally {
                _testingIds.value = emptySet()
            }
        }
    }

    /** Test all nodes, then auto-select the fastest reachable one. */
    fun autoSelectFastest(onDone: (Node?) -> Unit = {}) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val list = withContext(Dispatchers.IO) { dao.getAll() }
            if (list.isEmpty()) {
                onDone(null)
                return@launch
            }
            val pending = list.filter { !_testingIds.value.contains(it.id) }
            _testingIds.value = _testingIds.value + pending.map { it.id }
            try {
                LatencyTester.testAll(
                    ctx,
                    pending,
                    ctx.concurrentTests
                ) { node, ms ->
                    withContext(Dispatchers.IO) { dao.updateLatency(node.id, ms) }
                    _testingIds.value = _testingIds.value - node.id
                }
            } finally {
                _testingIds.value = emptySet()
            }
            // Pick the fastest node with a valid measurement.
            val fastest = withContext(Dispatchers.IO) { dao.getAll() }
                .filter { it.latencyMs >= 0 }
                .minByOrNull { it.latencyMs }
            if (fastest != null) selectNode(fastest)
            onDone(fastest)
        }
    }

    /**
     * Batch import from pasted text: one share link per line,
     * or a base64 subscription blob. Returns (added, failed, errors).
     */
    fun addNodesFromText(rawText: String, onDone: (SubscriptionManager.ImportResult) -> Unit) {
        viewModelScope.launch {
            val (nodes, errors) = withContext(Dispatchers.Default) {
                SubscriptionManager.parseBatch(rawText)
            }
            var added = 0
            withContext(Dispatchers.IO) {
                val hadAny = dao.getAll().isNotEmpty()
                for (node in nodes) {
                    dao.insert(node)
                    added++
                }
                // Auto-select the first node if the library was empty.
                if (!hadAny && added > 0) {
                    dao.getAll().firstOrNull()?.let {
                        _selectedId.value = it.id
                        dao.clearSelection()
                        dao.selectById(it.id)
                    }
                }
            }
            onDone(
                SubscriptionManager.ImportResult(
                    added = added,
                    failed = errors.size,
                    errors = errors.take(5)
                )
            )
        }
    }

    /** Fetch a subscription URL and import all nodes found. */
    fun addNodesFromSubscription(subUrl: String, onDone: (Result<SubscriptionManager.ImportResult>) -> Unit) {
        viewModelScope.launch {
            val fetchResult = SubscriptionManager.fetchLinks(subUrl)
            fetchResult.fold(
                onSuccess = { links ->
                    if (links.isEmpty()) {
                        onDone(Result.success(SubscriptionManager.ImportResult(0, 0, listOf("订阅中没有节点"))))
                    } else {
                        addNodesFromText(links.joinToString("\n"), { onDone(Result.success(it)) })
                    }
                },
                onFailure = { t -> onDone(Result.failure(t)) }
            )
        }
    }

    fun consumeNotice() {
        _notice.value = null
    }
}
