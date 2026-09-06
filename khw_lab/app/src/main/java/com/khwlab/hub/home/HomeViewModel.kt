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
        val cached = clients[updated.id]
        if (cached != null && !cached.matches(updated)) {
            // 设备换 IP:重建客户端,避免 probe/stream 一直打旧地址
            clients[updated.id] = DeviceClient(updated)
        }
    }

    fun clientFor(device: Device): DeviceClient =
        clients.getOrPut(device.id) { DeviceClient(device) }

    fun rename(id: String, newName: String) {
        if (newName.isBlank()) return
        store.rename(id, newName)
        _devices.update { list ->
            list.map { if (it.id == id) it.copy(name = newName) else it }
        }
    }

    fun delete(id: String) {
        store.delete(id)
        clients.remove(id)
        _devices.update { list -> list.filterNot { it.id == id } }
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
