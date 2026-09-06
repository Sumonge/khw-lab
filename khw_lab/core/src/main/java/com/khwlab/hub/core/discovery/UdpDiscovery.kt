package com.khwlab.hub.core.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.khwlab.hub.core.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * UDP 广播兜底发现(mDNS 不兼容手机的备用通道,PLAN §2 B2)。
 *
 * 固件契约(见 board-camera.ino):
 * - STA 模式监听 UDP 3333;
 * - 收到 magic "KHLAB_DISCOVER" 立即回一包 JSON;
 * - 之后每 2s 向 255.255.255.255:3333 广播 JSON(内容 {id, type, fwVersion, host, port})。
 *
 * 这里:每次 [discover] 发一次查询、收 [collectMs] 毫秒广播,解析出 Device 发到 [devices]。
 * 由调用方周期性调用(和心跳轮询同一节奏),与 mDNS 结果在宿主层按 id 去重合并。
 */
class UdpDiscovery(private val context: Context) {

    companion object {
        const val PORT = 3333
        const val MAGIC = "KHLAB_DISCOVER"
        private const val BROADCAST = "255.255.255.255"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val _devices = MutableSharedFlow<Device>(extraBufferCapacity = 16)
    val devices: SharedFlow<Device> = _devices.asSharedFlow()

    /** 发一次查询,收 [collectMs] 毫秒广播。 */
    suspend fun discover(collectMs: Long = 3000) = withContext(Dispatchers.IO) {
        val lock = (context.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("khwlab-udp")
        lock.setReferenceCounted(false)
        lock.acquire()
        try {
            // 必须绑 PORT:固件查询应答与周期广播都发往 255.255.255.255:3333,
            // 绑随机端口将永远收不到(见 board-camera.ino startUdp/handleUdp)。
            val socket = DatagramSocket(PORT).apply {
                broadcast = true
                soTimeout = 300
            }
            try {
                val query = DatagramPacket(
                    MAGIC.toByteArray(), MAGIC.length,
                    InetAddress.getByName(BROADCAST), PORT,
                )
                socket.send(query)

                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + collectMs
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        socket.receive(pkt)
                        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                        val fallbackHost = pkt.address?.hostAddress
                        parse(text, fallbackHost)?.let { _devices.tryEmit(it) }
                    } catch (_: SocketTimeoutException) {
                        // 继续等到 deadline
                    }
                }
            } finally {
                socket.close()
            }
        } finally {
            lock.release()
        }
    }

    /** 解析固件广播 JSON。host 优先取 JSON,取不到用发包方地址兜底。 */
    private fun parse(text: String, fallbackHost: String?): Device? {
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return null
        }
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val host = obj["host"]?.jsonPrimitive?.contentOrNull ?: fallbackHost ?: return null
        val port = obj["port"]?.jsonPrimitive?.intOrNull ?: 80
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
        return Device(id = id, type = type, host = host, port = port, name = name)
    }
}
