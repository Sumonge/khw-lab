package com.khwlab.hub.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.khwlab.hub.core.discovery.DeviceDiscovery
import com.khwlab.hub.core.discovery.UdpDiscovery
import com.khwlab.hub.core.model.Device
import com.khwlab.hub.core.net.DeviceClient
import com.khwlab.hub.core.store.DeviceStore
import com.khwlab.hub.store.PreferencesDeviceStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.buildJsonObject

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val discovery = DeviceDiscovery(application)
    private val udpDiscovery = UdpDiscovery(application)
    private val store: DeviceStore = PreferencesDeviceStore(application)

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    // 一个设备缓存一个客户端,让 probe 与 stream 共享同一份在线状态
    private val clients = mutableMapOf<String, DeviceClient>()

    init {
        viewModelScope.launch {
            discovery.devices.collect { d -> upsert(d) }
        }
        viewModelScope.launch {
            udpDiscovery.devices.collect { d -> upsert(d) }
        }
        discovery.start()
        startProbing()
        startUdpSweep()
    }

    /** 合并新发现:过滤已删除 + 应用本地显示名;同名去重。 */
    private fun upsert(d: Device) {
        if (store.isDeleted(d.id)) return
        val named = d.copy(name = store.displayName(d.id) ?: d.name)
        val changed = _devices.updateAndGet { list ->
            val existing = list.firstOrNull { it.id == named.id }
            if (existing != null) {
                // 保留用户改名,但 IP/端口/类型以新发现为准(可能换过网段,类型随 mDNS 解析)
                list.map {
                    if (it.id == named.id) {
                        it.copy(type = named.type, host = named.host, port = named.port)
                    } else {
                        it
                    }
                }
            } else {
                list + named
            }
        }
        val updated = changed.firstOrNull { it.id == named.id } ?: named
        // 复用/重建逻辑集中在 clientFor:地址变化、类型变化或配网后 token 变化都会重建
        clientFor(updated)
    }

    /** 复用/重建逻辑集中在这里:地址变化、类型变化或配网后 token 变化都会重建。UI 也经此取客户端。 */
    fun clientFor(d: Device): DeviceClient {
        val existing = clients[d.id]
        val token = store.token(d.id)
        // 已缓存且 token 一致 → 复用;地址/类型变化或配网后新拿到 token → 重建
        if (existing != null && existing.token == token && existing.matches(d)) return existing
        return DeviceClient(d, token).also { clients[d.id] = it }
    }

    fun rename(id: String, newName: String) {
        if (newName.isBlank()) return
        store.rename(id, newName)
        _devices.update { list ->
            list.map { if (it.id == id) it.copy(name = newName) else it }
        }
    }

    /**
     * 删除设备 = 本地隐藏 + 尝试让设备真正断网:
     * 有客户端缓存(最近发现过)时对固件 POST /unpair,清其 WiFi 凭据重启回配网态,
     * 之后不再自动发现。删除后用户仍可重新配网找回。
     */
    fun delete(id: String) {
        store.delete(id)
        val client = clients.remove(id)
        _devices.update { list -> list.filterNot { it.id == id } }
        if (client != null) {
            viewModelScope.launch {
                supervisorScope {
                    try {
                        client.post("/unpair", buildJsonObject { })
                    } catch (_: Exception) {
                        // 设备离线/不可达:本地删除已完成,固件侧下次通电仍是已配网状态,属可接受降级
                    }
                }
            }
        }
    }

    private fun startProbing() {
        viewModelScope.launch {
            while (true) {
                val snapshot = _devices.value
                for (d in snapshot) {
                    clientFor(d).probe()
                }
                delay(5000)
            }
        }
    }

    /** UDP 兜底:每 6s 扫一次,3s 收集窗口,与 5s 心跳错开。 */
    private fun startUdpSweep() {
        viewModelScope.launch {
            while (true) {
                try {
                    udpDiscovery.discover(collectMs = 3000)
                } catch (e: Exception) {
                    // 单次失败忽略,下轮再试
                }
                delay(3000)
            }
        }
    }

    override fun onCleared() {
        discovery.stop()
    }
}
