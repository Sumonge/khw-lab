package com.khwlab.hub.core.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.khwlab.hub.core.model.Device
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * mDNS 发现层。当前以 NsdManager 为主(服务类型 _esp32cam._tcp)。
 * 后续每加一种设备,只需往 [serviceTypes] 里加对应服务类型。
 *
 * 备用通道:UDP 广播兜底发现见 [UdpDiscovery](固件需监听 UDP 3333)。
 */
class DeviceDiscovery(private val context: Context) {

    private val serviceTypes = listOf("_esp32cam._tcp", "_esp32lock._tcp")

    private val _devices = MutableSharedFlow<Device>(extraBufferCapacity = 16)
    val devices: SharedFlow<Device> = _devices.asSharedFlow()

    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // 持有 resolve 回调,防止被 GC 提前回收
    private val resolvers = mutableListOf<NsdManager.ResolveListener>()

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        @Suppress("DEPRECATION") // resolveService 在 API 34+ 弃用,仍可用;后续可迁 registerServiceInfoCallback
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            val resolver = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                @Suppress("DEPRECATION") // info.host 在 API 34+ 弃用,可用 hostAddresses 替代
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    // NsdServiceInfo.serviceType 实际带完整后缀 "_esp32cam._tcp.local.",
                    // 剥干净才和插件 type("esp32cam") 对上,否则进详情页显示"未知设备类型"
                    val type = info.serviceType
                        .removePrefix("_")
                        .removeSuffix("._tcp")
                        .removeSuffix(".local.")
                    _devices.tryEmit(
                        Device(
                            id = info.serviceName,
                            type = type,
                            host = host,
                            port = info.port,
                            name = info.serviceName,
                        )
                    )
                }
            }
            resolvers.add(resolver)
            nsdManager.resolveService(serviceInfo, resolver)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            // 在线状态由 DeviceClient 心跳判定,这里不直接删卡
        }

        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    fun start() {
        for (t in serviceTypes) {
            try {
                nsdManager.discoverServices(t, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (_: Exception) {
        }
    }
}
