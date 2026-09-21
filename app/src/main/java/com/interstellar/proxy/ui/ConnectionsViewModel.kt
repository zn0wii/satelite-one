package com.interstellar.proxy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interstellar.proxy.core.ClashApiClient
import com.interstellar.proxy.core.CoreKind
import com.interstellar.proxy.core.MihomoCore
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.utils.CommandClient
import com.interstellar.proxy.utils.CommandTarget
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One active connection with live traffic (ui-facing snapshot). */
data class ActiveConnection(
    val id: String,
    val domain: String,
    val destination: String,
    val network: String,
    val protocol: String,
    val rule: String,
    val chains: List<String>,
    /** Originating process (mihomo metadata; null when the core doesn't report it). */
    val process: String? = null,
    val createdAt: Long,
    val uplink: Long,
    val downlink: Long,
    val closed: Boolean,
)

class ConnectionsViewModel(application: Application) : AndroidViewModel(application) {

    private val _connections = MutableStateFlow<List<ActiveConnection>>(emptyList())
    val connections: StateFlow<List<ActiveConnection>> = _connections

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var pollJob: Job? = null

    // libbox built-in store — applies event deltas correctly and evicts closed
    private val store = Libbox.newConnections()

    private val client = CommandClient(
        viewModelScope,
        CommandClient.ConnectionType.Connections,
        object : CommandClient.Handler {
            override fun onConnected() {
                _connected.value = true
            }

            override fun onDisconnected() {
                _connected.value = false
            }

            override fun onConnectionError(kind: CommandClient.ConnectionErrorKind, message: String) {
                _connected.value = false
            }

            override fun writeConnectionEvents(events: ConnectionEvents) {
                kotlinx.coroutines.runBlocking { publish(events) }
            }
        },
    )

    private val clashApi by lazy { ClashApiClient(MihomoCore.API_PORT, Settings.apiSecret) }

    private suspend fun publish(events: ConnectionEvents) = withContext(Dispatchers.Default) {
        store.applyEvents(events)
        store.filterState(Libbox.ConnectionStateAll.toInt())
        val list = mutableListOf<ActiveConnection>()
        val iterator = store.iterator()
        while (iterator.hasNext()) {
            val c = iterator.next()
            list.add(
                ActiveConnection(
                    id = c.id,
                    domain = c.domain.ifBlank { c.destination },
                    destination = c.destination,
                    network = c.network,
                    protocol = c.protocol,
                    rule = c.rule,
                    chains = buildList {
                        val it = c.chain()
                        while (it.hasNext()) add(it.next())
                    },
                    createdAt = c.createdAt,
                    uplink = c.uplinkTotal,
                    downlink = c.downlinkTotal,
                    closed = c.closedAt > 0,
                ),
            )
        }
        list.sortWith(compareBy({ !it.closed }, { -it.createdAt }))
        _connections.value = list
    }

    fun connect() {
        when (Settings.coreKind) {
            CoreKind.MIHOMO -> connectMihomo()

            // Xray has no connections API — the page shows its empty state
            CoreKind.XRAY -> Unit

            CoreKind.SINGBOX -> client.connect()
        }
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(1500)
                when (Settings.coreKind) {
                    CoreKind.MIHOMO -> pollMihomoOnce()

                    CoreKind.XRAY -> Unit

                    CoreKind.SINGBOX -> if (!_connected.value) client.connect()
                }
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        client.disconnect()
    }

    // ---- mihomo: Clash /connections snapshots (no event stream needed) ----

    private fun connectMihomo() {
        viewModelScope.launch { pollMihomoOnce() }
    }

    private suspend fun pollMihomoOnce() {
        val snapshot = runCatching { clashApi.connections() }.getOrNull()
        if (snapshot == null) {
            _connected.value = false
            return
        }
        val entries = snapshot["connections"] as? JsonArray ?: JsonArray(emptyList())
        val now = System.currentTimeMillis()
        val list = entries.mapNotNull { entry ->
            val c = entry.jsonObject
            val meta = c["metadata"]?.jsonObject ?: return@mapNotNull null
            val str = { key: String ->
                meta[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            }
            val id = c["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val host = str("host") ?: str("sniffHost")
            val destIp = str("destinationIP") ?: ""
            val destPort = str("destinationPort") ?: ""
            val destination = if (destIp.isBlank()) destPort else "$destIp:$destPort"
            ActiveConnection(
                id = id,
                domain = host?.takeIf { it.isNotBlank() } ?: destination,
                destination = destination,
                network = str("network") ?: "tcp",
                protocol = str("type") ?: "Mixed",
                rule = listOfNotNull(
                    c["rule"]?.jsonPrimitive?.content,
                    c["rulePayload"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                ).joinToString("(").let { if (it.contains("(")) "$it)" else it },
                chains = (c["chains"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                process = str("processPath")?.substringAfterLast('/')
                    ?: str("process")?.takeIf { it.isNotBlank() },
                createdAt = parseClashTime(c["start"]?.jsonPrimitive?.content) ?: now,
                uplink = c["upload"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                downlink = c["download"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                closed = false,
            )
        }
        list.sortedByDescending { it.createdAt }
        _connections.value = list
        _connected.value = true
    }

    private fun parseClashTime(value: String?): Long? = runCatching {
        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrNull()

    fun closeConnection(id: String) {
        when (Settings.coreKind) {
            CoreKind.MIHOMO -> viewModelScope.launch {
                runCatching { clashApi.deleteConnection(id) }
            }

            CoreKind.XRAY -> Unit

            CoreKind.SINGBOX -> viewModelScope.launch {
                runCatching { CommandTarget.standaloneClient().closeConnection(id) }
            }
        }
    }

    fun closeAll() {
        when (Settings.coreKind) {
            CoreKind.MIHOMO -> viewModelScope.launch {
                runCatching { clashApi.closeAllConnections() }
            }

            CoreKind.XRAY -> Unit

            CoreKind.SINGBOX -> viewModelScope.launch {
                runCatching { CommandTarget.standaloneClient().closeConnections() }
            }
        }
    }
}
