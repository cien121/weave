package com.chan.weave.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chan.weave.CianCoreApp
import com.chan.weave.core.CoreType
import com.chan.weave.core.LatencyTester
import com.chan.weave.data.DnsPrefs.DEFAULT_IP_INFO_URL
import com.chan.weave.data.DnsPrefs.concurrentTests
import com.chan.weave.data.DnsPrefs.ipInfoUrl
import com.chan.weave.data.IpChecker
import com.chan.weave.data.Node
import com.chan.weave.data.NodeDao
import com.chan.weave.data.NodeLinkParser
import com.chan.weave.data.NodeShortcutManager
import com.chan.weave.data.SubscriptionManager
import com.chan.weave.mist.MistVpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tunnel connection state, shared by HomeScreen and the tile. */
enum class ConnectionState { IDLE, CONNECTING, CONNECTED, ERROR }

/**
 * Home/Nodes shared view model. Weave is Mist-only since 1.0.30:
 * the only tunnel backend is the independent Mist framework
 * ([MistVpnController]). Xray was fully removed (use SwiftNG for Xray).
 * Nodes come from Room; the selected node drives connect().
 * Only mist:// nodes can connect; anything else gets a notice.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao: NodeDao = CianCoreApp.instance.database.nodeDao()

    /** Mist tunnel state, mirrored from [MistVpnController]. */
    private val _mistState = MutableStateFlow(ConnectionState.IDLE)
    private val _mistError = MutableStateFlow<String?>(null)
    private val _mistActiveNodeId = MutableStateFlow<Long?>(null)

    val connectionState: StateFlow<ConnectionState> = _mistState.asStateFlow()
    val connectionError: StateFlow<String?> = _mistError.asStateFlow()
    val activeNodeId: StateFlow<Long?> = _mistActiveNodeId.asStateFlow()

    val nodes: StateFlow<List<Node>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _egressIp = MutableStateFlow<IpChecker.IpInfo?>(null)
    val egressIp: StateFlow<IpChecker.IpInfo?> = _egressIp.asStateFlow()

    /**
     * Refresh egress IP through the Mist engine (never direct HTTP:
     * the app's own traffic bypasses the VPN, so direct would return
     * the real IP). Mist production egress runs over VPS IPv6.
     */
    private fun refreshEgress() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val url = ctx.ipInfoUrl.trim().ifEmpty { DEFAULT_IP_INFO_URL }
            _egressIp.value = IpChecker.checkViaEngine(MistVpnController.engine, url)
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

    /** Engine diagnostics, polled while connected (0 = healthy). */
    data class EngineDiagnostics(
        val tunWriteErrors: Long = 0,
        val acksSent: Long = 0,
        val ackSendErrors: Long = 0,
        val datagramsSent: Long = 0,
        val datagramsSendErrors: Long = 0,
        val datagramsReceived: Long = 0
    )
    private val _diagnostics = MutableStateFlow(EngineDiagnostics())
    val diagnostics: StateFlow<EngineDiagnostics> = _diagnostics.asStateFlow()

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
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    refreshEgress()
                } else if (state == ConnectionState.IDLE) {
                    _egressIp.value = null
                }
            }
        }
        // Mirror the Mist framework state, and compute traffic speed
        // from its polled byte counters.
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
                _mistActiveNodeId.value = null
            }
        }
        viewModelScope.launch {
            var lastUp = 0L
            var lastDown = 0L
            var lastAt = 0L
            while (isActive) {
                if (connectionState.value == ConnectionState.CONNECTED) {
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
                        _diagnostics.value = EngineDiagnostics(
                            tunWriteErrors = MistVpnController.tunWriteErrors,
                            acksSent = MistVpnController.acksSent,
                            ackSendErrors = MistVpnController.ackSendErrors,
                            datagramsSent = MistVpnController.datagramsSent,
                            datagramsSendErrors = MistVpnController.datagramsSendErrors,
                            datagramsReceived = MistVpnController.datagramsReceived
                        )
                    } catch (_: Throwable) {
                    }
                } else {
                    lastAt = 0L
                    if (_trafficSpeed.value != TrafficSpeed()) {
                        _trafficSpeed.value = TrafficSpeed()
                    }
                    if (_diagnostics.value != EngineDiagnostics()) {
                        _diagnostics.value = EngineDiagnostics()
                    }
                }
                delay(1000)
            }
        }
    }

    /** A node is connectable when its link uses mist://. */
    private fun isMistNode(node: Node?): Boolean =
        node?.rawLink?.trim()?.startsWith("mist://") == true

    /** Pending mist:// link waiting on the VPN permission dialog. */
    private var pendingMistLink: String? = null
    private var pendingMistNodeId: Long? = null

    /** Start a mist:// tunnel. Caller must hold VPN permission. */
    private fun startMist(ctx: Application, node: Node) {
        _mistActiveNodeId.value = node.id
        _mistState.value = ConnectionState.CONNECTING
        MistVpnController.start(ctx, node.rawLink.trim())
    }

    /**
     * Orb tap. When disconnected: connect the selected node.
     * Only mist:// nodes work; anything else gets a notice.
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
                if (!isMistNode(node)) {
                    _notice.value = "只支持 mist:// 节点"
                    return
                }
                val permissionIntent = MistVpnController.prepareIntent(ctx)
                if (permissionIntent != null) {
                    pendingMistLink = node!!.rawLink.trim()
                    pendingMistNodeId = node.id
                    requestVpnPermission(permissionIntent)
                } else {
                    startMist(ctx, node!!)
                }
            }
            ConnectionState.CONNECTING -> { /* tap does nothing mid-flight */ }
            ConnectionState.CONNECTED -> {
                _mistActiveNodeId.value = null
                MistVpnController.stop(getApplication())
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
            if (node != null && isMistNode(node)) {
                startMist(getApplication(), node)
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
                    if (!isMistNode(node)) {
                        _notice.value = "只支持 mist:// 节点"
                        return@launch
                    }
                    val permissionIntent = MistVpnController.prepareIntent(ctx)
                    if (permissionIntent != null) {
                        pendingMistLink = node.rawLink.trim()
                        pendingMistNodeId = node.id
                        requestVpnPermission(permissionIntent)
                    } else {
                        startMist(ctx, node)
                    }
                }
                ConnectionState.CONNECTED -> {
                    // Switch to this node: tear down first, then start.
                    MistVpnController.stop(ctx)
                    delay(500)
                    if (!isMistNode(node)) {
                        _notice.value = "只支持 mist:// 节点"
                        return@launch
                    }
                    startMist(ctx, node)
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

    /** Connection test for one node (Mist-only: handshake round-trip). */
    fun testRealConnection(node: Node) {
        if (_testingIds.value.contains(node.id)) return
        _testingIds.value = _testingIds.value + node.id
        viewModelScope.launch {
            try {
                val ms = LatencyTester.realTest(getApplication(), node)
                withContext(Dispatchers.IO) { dao.updateLatency(node.id, ms) }
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
            val node = Node(
                name = "新节点",
                rawLink = "",
                protocol = protocol,
                network = "mist",
                coreType = CoreType.MIST
            )
            val id = withContext(Dispatchers.IO) { dao.insert(node) }
            onDone(node.copy(id = id))
        }
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
