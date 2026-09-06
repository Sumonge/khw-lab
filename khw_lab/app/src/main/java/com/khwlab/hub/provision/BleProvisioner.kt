package com.khwlab.hub.provision

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * BLE 配网客户端(固件契约 CONTRACT §7)。
 *
 * 两板共用同一组特征, 服务 UUID 区分产品:
 *   SSID  Write ≤32B / PASS Write ≤64B / STATUS Read+Notify
 *   camera 服务 6b687763-… / lock 服务 6b68776c-…
 *
 * 关键点:
 * - SSID(32B)与 PASS(64B)都超过默认 ATT MTU-3=20B 单包负载, 连接后必须先
 *   requestMtu(≥68, 建议 256) 再写, 否则长写入被对端拒绝。
 * - 必须**先订阅 STATUS 的 CCCD** 再写 SSID:固件 notifyStatus 只在已订阅时发,
 *   不订阅就永远收不到三态结果(见 ble.cpp KhwStatusCallbacks)。
 * - 设备 notify 三态:{"status":"connecting"} / {"status":"ok","token":"…","host":"…"}
 *   / {"status":"fail","error":"wifi"}; ok 后设备 1s 重启入网。
 */
class BleProvisioner(private val context: Context) {

    companion object {
        // 与固件 lib_common.h 的 UUID 完全一致
        const val SERVICE_UUID_CAM = "6b687763-0000-1000-8000-00805f9b34fb"
        const val SERVICE_UUID_LOCK = "6b68776c-0000-1000-8000-00805f9b34fb"
        const val CHAR_SSID = "6b6877d1-0000-1000-8000-00805f9b34fb"
        const val CHAR_PASS = "6b6877d2-0000-1000-8000-00805f9b34fb"
        const val CHAR_STATUS = "6b6877d3-0000-1000-8000-00805f9b34fb"
        const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

        /** 配网全程(连接 + MTU + 订阅 + 写凭据 + 等三态)超时。 */
        const val PROVISION_TIMEOUT_MS = 20_000L
        /** 设备 ok 后 1s 重启入网, App 在家庭 WiFi 上等 /status 可用的最长窗口。 */
        const val CONFIRM_TIMEOUT_MS = 15_000L
    }

    private val uuidSsid = UUID.fromString(CHAR_SSID)
    private val uuidPass = UUID.fromString(CHAR_PASS)
    private val uuidStatus = UUID.fromString(CHAR_STATUS)
    private val uuidCccd = UUID.fromString(CCCD_UUID)

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    /** 扫描到的一台待配网设备。type 仅用于列表标注(esp32cam / esp32lock)。 */
    data class BleDevice(
        val mac: String,
        val name: String?,
        val rssi: Int,
        val type: String,
    ) {
        val displayName: String get() = name ?: mac
    }

    /** 配网 ok 结果:设备在家庭 WiFi 上的 IP + 固件下发的共享令牌(§2)。deviceId 需另查 /status。 */
    data class ProvisionResult(val token: String, val host: String)

    // ---- 扫描(生命周期由调用方启停, 结果经 [onDevice] 实时推送) ----
    /** 按两个服务 UUID 过滤广播; 返回可传给 [stopScan] 的回调, 失败返回 null 并回调 [onError]。 */
    fun startScan(onDevice: (BleDevice) -> Unit, onError: (String) -> Unit): ScanCallback? {
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            onError("此设备不支持蓝牙")
            return null
        }
        if (!adapter.isEnabled) {
            onError("蓝牙未开启, 请先打开蓝牙开关")
            return null
        }
        val scanner = adapter.bluetoothLeScanner
            ?: run { onError("蓝牙扫描器不可用"); return null }

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(SERVICE_UUID_CAM)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(SERVICE_UUID_LOCK)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onDevice(toBleDevice(result))
            }

            override fun onScanFailed(errorCode: Int) {
                onError("蓝牙扫描失败(错误码 $errorCode), 请稍后重试")
            }
        }
        try {
            scanner.startScan(filters, settings, callback)
            return callback
        } catch (e: SecurityException) {
            onError("缺少蓝牙权限, 请授权后重试")
            return null
        } catch (e: Exception) {
            onError("扫描异常: ${e.message}")
            return null
        }
    }

    fun stopScan(callback: ScanCallback?) {
        if (callback == null) return
        try {
            bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {
        }
    }

    private fun toBleDevice(result: ScanResult): BleDevice {
        val name = result.scanRecord?.deviceName ?: result.device.name
        val uuids = result.scanRecord?.serviceUuids.orEmpty()
            .map { it.uuid.toString().lowercase() }
        val type = when {
            uuids.any { it == SERVICE_UUID_LOCK } -> "esp32lock"
            name?.startsWith("KHW-LOCK") == true -> "esp32lock"
            else -> "esp32cam" // CAM 服务 / KHW-CAM 名 / 无法判断默认摄像头
        }
        return BleDevice(
            mac = result.device.address,
            name = name,
            rssi = result.rssi,
            type = type,
        )
    }

    /**
     * 对 [device] 完成一次 BLE 配网, 阻塞直到三态之一返回。
     * 顺序:连接 → discoverServices → requestMtu(256) → 订阅 STATUS CCCD → 写 SSID → 写 PASS。
     * 成功返回 {token, host}; 失败抛异常; 超时抛 TimeoutCancellationException。
     * [onStatus] 在每次进度推进时回调, 用于 UI 显示进行中状态。
     */
    suspend fun provision(
        device: BleDevice,
        ssid: String,
        password: String,
        onStatus: (String) -> Unit = {},
    ): ProvisionResult {
        val adapter = bluetoothManager.adapter ?: throw IOException("蓝牙不可用")
        if (!adapter.isEnabled) throw IOException("蓝牙未开启")
        val remote = adapter.getRemoteDevice(device.mac)

        return withTimeout(PROVISION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var gatt: BluetoothGatt? = null
                var ssidWritten = false
                var finished = false

                fun finish(r: ProvisionResult) {
                    if (finished) return
                    finished = true
                    cont.resume(r)
                }

                fun fail(t: Throwable) {
                    if (finished) return
                    finished = true
                    cont.resumeWithException(t)
                }

                fun serviceOf(g: BluetoothGatt): BluetoothGattService? =
                    g.services.firstOrNull { s ->
                        val u = s.uuid.toString().lowercase()
                        u == SERVICE_UUID_CAM || u == SERVICE_UUID_LOCK
                    }

                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(
                        g: BluetoothGatt,
                        status: Int,
                        newState: Int,
                    ) {
                        gatt = g
                        when {
                            newState == BluetoothProfile.STATE_CONNECTED &&
                                status == BluetoothGatt.GATT_SUCCESS ->
                                g.discoverServices()

                            newState == BluetoothProfile.STATE_DISCONNECTED ->
                                fail(IOException("与设备的蓝牙连接已断开"))

                            status != BluetoothGatt.GATT_SUCCESS ->
                                fail(IOException("蓝牙连接失败(status=$status)"))
                        }
                    }

                    @Suppress("DEPRECATION") // onServicesDiscovered 在 API 33+ 弃用, 仍是 Android 12- 的唯一路径
                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            fail(IOException("发现设备服务失败(status=$status)"))
                            return
                        }
                        // SSID/PASS 超 20B 单包负载, 必须先扩 MTU(固件端 NimBLE 支持)
                        g.requestMtu(256)
                    }

                    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                        // MTU 协商失败也继续:短 SSID 的裸网络可能仍可写, 由写入结果兜底
                        val svc = serviceOf(g)
                            ?: run { fail(IOException("设备未暴露配网服务")); return }
                        val statusChar = svc.getCharacteristic(uuidStatus)
                            ?: run { fail(IOException("设备缺少状态特征")); return }
                        if (!g.setCharacteristicNotification(statusChar, true)) {
                            fail(IOException("无法订阅设备状态通知"))
                            return
                        }
                        val cccd = statusChar.getDescriptor(uuidCccd)
                            ?: run { fail(IOException("设备缺少订阅描述符")); return }
                        // 先订阅 STATUS 再写 SSID:固件只在已订阅时 notify
                        cccd.value = byteArrayOf(0x01, 0x00)
                        g.writeDescriptor(cccd)
                    }

                    @Suppress("DEPRECATION") // onDescriptorWrite 在 API 33+ 弃用, 仍兼容
                    override fun onDescriptorWrite(
                        g: BluetoothGatt,
                        descriptor: BluetoothGattDescriptor,
                        status: Int,
                    ) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            fail(IOException("订阅失败(status=$status)"))
                            return
                        }
                        onStatus("已连接, 正在下发 WiFi 名称…")
                        val svc = serviceOf(g)
                            ?: run { fail(IOException("设备未暴露配网服务")); return }
                        val ssidChar = svc.getCharacteristic(uuidSsid)
                            ?: run { fail(IOException("设备缺少 SSID 特征")); return }
                        if (ssid.isBlank() || ssid.toByteArray().size > 32) {
                            fail(IOException("SSID 不能为空且不超过 32 字节"))
                            return
                        }
                        ssidChar.value = ssid.toByteArray()
                        g.writeCharacteristic(ssidChar)
                    }

                    override fun onCharacteristicWrite(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            fail(IOException("写入失败(status=$status)"))
                            return
                        }
                        if (!ssidWritten) {
                            ssidWritten = true
                            onStatus("已下发 WiFi 名称, 正在下发密码…")
                            val svc = serviceOf(g)
                                ?: run { fail(IOException("设备未暴露配网服务")); return }
                            val passChar = svc.getCharacteristic(uuidPass)
                                ?: run { fail(IOException("设备缺少密码特征")); return }
                            if (password.toByteArray().size > 64) {
                                fail(IOException("密码不能超过 64 字节"))
                                return
                            }
                            passChar.value = password.toByteArray()
                            g.writeCharacteristic(passChar)
                        }
                        // PASS 写完后设备自行连 WiFi 并 notify STATUS, 无需再动作
                    }

                    override fun onCharacteristicChanged(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                    ) {
                        if (characteristic.uuid.toString().lowercase() != CHAR_STATUS) return
                        handleStatus(
                            String(value, Charsets.UTF_8).trim(),
                            onStatus,
                            ::finish,
                            ::fail,
                        )
                    }
                }

                cont.invokeOnCancellation {
                    try { gatt?.disconnect() } catch (_: Exception) {}
                    try { gatt?.close() } catch (_: Exception) {}
                }

                try {
                    gatt = remote.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                } catch (e: SecurityException) {
                    fail(IOException("缺少蓝牙权限"))
                }
                if (gatt == null && !cont.isCompleted) {
                    fail(IOException("无法建立蓝牙连接"))
                }
            }
        }
    }

    /** 解析设备 notify 的三态 JSON(免库字符串解析, 与固件拼串结构对应)。 */
    private fun handleStatus(
        text: String,
        onStatus: (String) -> Unit,
        finish: (ProvisionResult) -> Unit,
        fail: (Throwable) -> Unit,
    ) {
        when {
            text.contains("\"connecting\"") ->
                onStatus("设备正在连接家庭 WiFi…")

            text.contains("\"ok\"") -> {
                val token = extract(text, "\"token\":\"") ?: run {
                    fail(IOException("设备未返回令牌")); return
                }
                val host = extract(text, "\"host\":\"") ?: run {
                    fail(IOException("设备未返回 IP")); return
                }
                finish(ProvisionResult(token, host))
            }

            text.contains("\"fail\"") ->
                fail(IOException("WiFi 连接失败: 请检查 SSID 与密码是否正确"))

            else -> onStatus(text)
        }
    }

    /** 从 `"key":"value"` 形式的 JSON 串里抠出 value。 */
    private fun extract(text: String, key: String): String? {
        val i = text.indexOf(key)
        if (i < 0) return null
        val j = text.indexOf('"', i + key.length)
        if (j < 0) return null
        return text.substring(i + key.length, j)
    }
}
