package com.khwlab.hub.provision

import android.Manifest
import android.app.Application
import android.bluetooth.le.ScanCallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.khwlab.hub.core.model.Device
import com.khwlab.hub.core.net.DeviceClient
import com.khwlab.hub.core.net.bool
import com.khwlab.hub.core.net.str
import com.khwlab.hub.core.store.DeviceStore
import com.khwlab.hub.store.PreferencesDeviceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val DEVICE_AP_SSID = "ESP32-CAM-Setup"
const val DEVICE_AP_SSID_LOCK = "KHW-LOCK-Setup"

data class ProvisionState(
    /** 配网方式:true=蓝牙(BLE 优先), false=热点(SoftAP 兜底)。 */
    val bleMode: Boolean = true,
    val bleDevices: List<BleProvisioner.BleDevice> = emptyList(),
    val bleScanning: Boolean = false,
    val selectedBleMac: String? = null,
    val deviceApSsid: String = DEVICE_AP_SSID,
    val homeSsid: String = "",
    val homePassword: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val done: Boolean = false,
    val error: String? = null,
)
/** Wi-Fi 扫描状态(配网页内嵌的"选择附近网络"弹窗用)。 */
data class WifiScanState(
    val pickerOpen: Boolean = false,
    val ssids: List<String> = emptyList(),
    val selectedSsid: String = "",
    val scanning: Boolean = false,
    val pendingPermissionRequest: Boolean = false,
    val error: String? = null,
)

class ProvisionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val wifiManager = application.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connector = WifiConnector(application)
    private val store: DeviceStore = PreferencesDeviceStore(application)
    private val bleProvisioner = BleProvisioner(application)

    private val _state = MutableStateFlow(ProvisionState())
    val state: StateFlow<ProvisionState> = _state.asStateFlow()

    private val _wifi = MutableStateFlow(WifiScanState())
    val wifi: StateFlow<WifiScanState> = _wifi.asStateFlow()

    private var lastScanAt = 0L

    private val scanReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION") // scanResults / SSID 字段在 API 33+ 弃用,仍可用
        override fun onReceive(context: Context?, intent: Intent?) {
            val results: List<ScanResult> = try {
                wifiManager.scanResults ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val ssids = results.mapNotNull { it.SSID }.filter { it.isNotBlank() }.distinct().sorted()
            if (ssids.isEmpty()) {
                // 空结果:给出可操作的指引(权限/定位开关/信号),而不是无声无息
                _wifi.update { it.copy(ssids = emptyList(), scanning = false, error = scanEmptyHint()) }
            } else {
                _wifi.update { it.copy(ssids = ssids, scanning = false, error = null) }
            }
        }
    }

    private var receiverRegistered = false

    init {
        registerScanReceiver()
    }

    private fun registerScanReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(scanReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(scanReceiver, filter)
        }
        receiverRegistered = true
    }

    /** 弹窗打开时每 6s 自动重扫一次,保证列表常新。 */
    private var autoRescanJob: kotlinx.coroutines.Job? = null

    @Suppress("DEPRECATION") // startScan() 在 API 33+ 弃用,仍可用
    fun startScan() {
        val now = android.os.SystemClock.elapsedRealtime()
        // 限频:3s 内重复点"刷新"不重复扫描(Android 有限频,连续点会白刷)
        if (now - lastScanAt < 3000L && _wifi.value.scanning) return
        lastScanAt = now
        _wifi.update { it.copy(scanning = true, error = null) }
        try {
            val ok = wifiManager.startScan()
            if (!ok) {
                // false = 系统拒绝本次扫描(常见于刚扫完的限频窗口),给用户明确反馈而不是静默失败
                _wifi.update {
                    it.copy(
                        scanning = false,
                        error = "扫描被系统限频了,请稍等几秒再点刷新(别连续点)。",
                    )
                }
            }
        } catch (e: SecurityException) {
            _wifi.update {
                it.copy(
                    scanning = false,
                    error = if (Build.VERSION.SDK_INT >= 33) {
                        "缺少「附近设备」权限,扫描不到 WiFi。请点下方按钮授权。"
                    } else {
                        "缺少「位置」权限,扫描不到 WiFi。请点下方按钮授权。"
                    },
                )
            }
        } catch (e: Exception) {
            _wifi.update { it.copy(scanning = false, error = "扫描失败: ${e.message}") }
        }
    }

    /** 是否已向系统请求过一次权限(请求过一次未授权 = 系统不会再弹框,只能去设置)。 */
    private var requestedPermission = false

    fun hasRequestedPermission(): Boolean = requestedPermission

    /** 打开弹窗前先确保权限到位:缺权限就交给 Activity 弹系统授权框,授权完成自动开扫。 */
    fun openWifiPicker(
        requestPermissions: (() -> Unit)? = null,
    ) {
        registerScanReceiver()
        if (!hasRequiredPermissions()) {
            requestPermissions?.invoke()
            _wifi.update { it.copy(pickerOpen = true, pendingPermissionRequest = true) }
            return
        }
        if (!hasLocationServicesOn()) {
            _wifi.update {
                it.copy(pickerOpen = true, error = "请先打开手机的「位置信息」开关(部分系统在快速设置栏),否则扫不到 WiFi。")
            }
        }
        autoRescanJob?.cancel()
        autoRescanJob = viewModelScope.launch {
            while (true) {
                delay(6000)
                startScan()
            }
        }
        _wifi.update { it.copy(pickerOpen = true) }
        startScan()
    }

    /** 授权弹窗已处理:granted=true 立即开扫,false 给出明确原因(含去系统设置的手动入口)。 */
    fun onPermissionHandled(granted: Boolean) {
        requestedPermission = true // 只要系统弹过一次,之后就不能再靠弹框拿权限了
        _wifi.update {
            it.copy(
                pendingPermissionRequest = false,
                error = if (granted) {
                    null
                } else {
                    if (Build.VERSION.SDK_INT >= 33) {
                        "未授权:扫描 WiFi 需要「附近设备」+「位置」两个权限。若弹窗没出现或点了拒绝,请去系统设置→应用→khw_hub→权限里手动开启,再点刷新。"
                    } else {
                        "未授权:扫描 WiFi 需要「位置」权限。若弹窗没出现或点了拒绝,请去系统设置→应用→khw_hub→权限里手动开启,再点刷新。"
                    }
                },
            )
        }
        if (granted) {
            autoRescanJob?.cancel()
            autoRescanJob = viewModelScope.launch {
                while (true) {
                    delay(6000)
                    startScan()
                }
            }
            startScan()
        }
    }

    fun hasRequiredPermissions(): Boolean {
        // 33+ 扫描 WiFi 需要 NEARBY_WIFI_DEVICES,但无论如何(所有版本)系统
        // 都要 ACCESS_FINE_LOCATION 才返回 WifiManager 的扫描结果(SSID 列表)。
        // 两个都得给,缺一个就扫不出 WiFi —— 这是 "清除数据后权限正常但扫不到" 的根因。
        val nearby = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
        val location = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return nearby && location
    }

    private fun hasLocationServicesOn(): Boolean {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /** 扫描返回空结果时给用户的可操作提示(而不是无声无息)。 */
    private fun scanEmptyHint(): String {
        return when {
            !hasLocationServicesOn() ->
                "没扫到 WiFi:手机的「位置信息」开关未打开,请先打开(部分系统在快速设置栏),再点刷新。"
            !hasRequiredPermissions() ->
                if (Build.VERSION.SDK_INT >= 33) {
                    "没扫到 WiFi:缺少「附近设备」权限。请点系统弹窗允许,或在设置里为 khw_hub 开启后点刷新。"
                } else {
                    "没扫到 WiFi:缺少「位置」权限。请点系统弹窗允许,或在设置里为 khw_hub 开启后点刷新。"
                }
            else ->
                "没扫到 WiFi:可能不在信号范围,或系统对连续扫描限频。请靠近路由器,等几秒再点刷新。"
        }
    }

    fun closeWifiPicker() {
        autoRescanJob?.cancel()
        autoRescanJob = null
        _wifi.update { it.copy(pickerOpen = false) }
    }

    /** 从弹窗选择一个 SSID 填入家庭 WiFi。 */
    fun selectSsid(ssid: String) {
        _state.update { it.copy(homeSsid = ssid) }
        _wifi.update { it.copy(selectedSsid = ssid) }
    }

    fun setHomeSsid(v: String) = _state.update { it.copy(homeSsid = v) }
    fun setHomePassword(v: String) = _state.update { it.copy(homePassword = v) }
    fun setDeviceApSsid(v: String) = _state.update { it.copy(deviceApSsid = v) }

    // ---- BLE 配网路径(优先,CONTRACT §7)----

    /** 31+ 运行时需 BLUETOOTH_SCAN+BLUETOOTH_CONNECT;30- 扫描结果靠位置权限。 */
    fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private var requestedBlePermission = false

    fun hasRequestedBlePermission(): Boolean = requestedBlePermission

    /** BLE 授权弹窗已处理:granted=true 立即开扫,false 给出明确原因。 */
    fun onBlePermissionHandled(granted: Boolean) {
        requestedBlePermission = true
        if (granted) {
            startBleScan()
        } else {
            _state.update {
                it.copy(
                    bleScanning = false,
                    error = if (Build.VERSION.SDK_INT >= 31) {
                        "未授权蓝牙权限,扫描不到设备。若弹窗没出现或点了拒绝,请去系统设置→应用→khw_hub→权限里手动开启「附近的设备」后重试。"
                    } else {
                        "未授权位置权限,扫描不到蓝牙设备。请去系统设置→应用→khw_hub→权限里手动开启位置后重试。"
                    },
                )
            }
        }
    }

    private var bleScanCallback: ScanCallback? = null

    /** 开始扫描待配网设备;缺权限时静默返回(false),由 UI 决定是否弹授权。 */
    fun startBleScan(): Boolean {
        if (bleScanCallback != null) return true // 已在扫
        if (!hasBlePermissions()) return false
        _state.update { it.copy(bleScanning = true, error = null) }
        val cb = bleProvisioner.startScan(
            onDevice = { dev ->
                _state.update { st ->
                    val list = st.bleDevices.toMutableList()
                    list.removeAll { it.mac == dev.mac } // 同设备刷新,新到的排前面
                    list.add(0, dev)
                    st.copy(bleDevices = list.sortedByDescending { it.rssi })
                }
            },
            onError = { msg ->
                bleScanCallback = null
                _state.update { it.copy(bleScanning = false, error = msg) }
            },
        )
        bleScanCallback = cb
        return cb != null
    }

    fun stopBleScan() {
        bleProvisioner.stopScan(bleScanCallback)
        bleScanCallback = null
        _state.update { it.copy(bleScanning = false) }
    }

    fun selectBleDevice(mac: String) {
        _state.update { it.copy(selectedBleMac = mac, error = null) }
    }

    fun setBleMode(ble: Boolean) {
        if (ble == _state.value.bleMode) return
        stopBleScan()
        _state.update {
            it.copy(
                bleMode = ble,
                bleDevices = emptyList(),
                selectedBleMac = null,
                error = null,
                message = null,
            )
        }
    }

    /** BLE 配网:连接设备 → 写 SSID/PASS → 等 STATUS ok(内含 token+host)。 */
    fun bleProvision(device: BleProvisioner.BleDevice) {
        val s = _state.value
        if (s.homeSsid.isBlank()) {
            _state.update { it.copy(error = "请填写家庭 WiFi 名称") }
            return
        }
        viewModelScope.launch {
            stopBleScan()
            _state.update {
                it.copy(busy = true, error = null, message = "正在连接 ${device.displayName},准备下发 WiFi…")
            }
            try {
                val result = bleProvisioner.provision(device, s.homeSsid, s.homePassword) { msg ->
                    _state.update { it.copy(message = msg) }
                }
                // ok:设备 1s 后重启入网;手机仍在当前 WiFi,带背压轮询 /status 取 deviceId
                _state.update { it.copy(message = "配网成功,正在确认设备上线…") }
                val deviceId = confirmDeviceOnline(result.host)
                if (deviceId != null) {
                    saveTokenForDevice(device.type, deviceId, result.token)
                    store.clearDeleted()
                    _state.update {
                        it.copy(busy = false, done = true, message = "配网成功,设备已上线!")
                    }
                } else {
                    // /status 连不上(常见于手机不在该 WiFi 上)。锁板可凭广播名(KHW-LOCK-xxxx)
                    // 推导 mDNS 名兜底存 token;摄像头版缺 id,只能等首页发现后再补
                    saveLockTokenFromBleName(device, result.token)
                    store.clearDeleted()
                    _state.update {
                        it.copy(
                            busy = false,
                            done = true,
                            message = "配网成功!设备正在重启入网,首页稍后会看到它。\n" +
                                "(若手机当前不在这个 WiFi 上,连回该 WiFi 后首页才会发现设备)",
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = "配网超时(20 秒)。请确认设备处于配网模式(蓝色呼吸灯),再试一次。",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "配网失败: ${e.message}") }
            }
        }
    }

    /** 设备 ok 后重启入网,轮询 /status(免令牌)取 deviceId;不可达则返回 null。 */
    private suspend fun confirmDeviceOnline(host: String): String? {
        val client = DeviceClient(
            Device(id = "ble-confirm", type = "provision", host = host, port = 80, name = "")
        )
        val deadline = System.currentTimeMillis() + BleProvisioner.CONFIRM_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                val resp = client.get("/status")
                val id = resp.str("id")
                if (!id.isNullOrBlank()) return id
            } catch (_: Exception) {
                // 设备还在重启/未入网,稍后重试
            }
            delay(1500)
        }
        return null
    }

    /** 保存配网令牌。锁板 mDNS host 与 deviceId 不一致,补一份别名键(见改进清单)。 */
    private fun saveTokenForDevice(type: String, deviceId: String, token: String) {
        store.saveToken(deviceId, token)
        if (type == "esp32lock") {
            // 首页发现按 mDNS 名(khw-lock-xxxx)做 Device.id,再按该 id 查 token;
            // deviceId 是 esp32lock-xxxxxx,直接存会 401,故两键都存。
            store.saveToken("khw-lock-" + deviceId.takeLast(4), token)
        }
    }

    /** BLE 路径拿不到 deviceId 时,锁板可凭广播名 KHW-LOCK-xxxx 的尾 4 位推导 mDNS 名兜底。 */
    private fun saveLockTokenFromBleName(device: BleProvisioner.BleDevice, token: String) {
        if (device.type != "esp32lock") return
        val tail = device.name?.takeLast(4)
        if (tail != null && tail.length == 4 && tail.all { it.isLetterOrDigit() }) {
            store.saveToken("khw-lock-$tail", token)
        }
    }

    // ---- SoftAP 兜底路径 ----

    fun provision() {
        val s = _state.value
        if (s.homeSsid.isBlank()) {
            _state.update { it.copy(error = "请填写家庭 WiFi 名称") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = "正在连接设备热点 ${s.deviceApSsid}…(最多 30 秒)") }
            try {
                val connected = connector.connectToAp(s.deviceApSsid)
                if (!connected) {
                    // 热点不可见/超时(默认 30s):给出可操作指引。设备之前配过网时不会开热点,
                    // 需长按 BOOT 约 4s 清凭据重启回 AP 配网模式(固件 checkFactoryReset)。
                    _state.update {
                        it.copy(
                            busy = false,
                            error = "找不到设备热点 \"${s.deviceApSsid}\":请确认设备已开机且处于配网模式。" +
                                "若设备之前配过网,需长按设备上的 BOOT 按钮约 4 秒清除 WiFi 凭据并重启后再试。",
                        )
                    }
                    return@launch
                }
                _state.update { it.copy(message = "正在下发 WiFi 凭据…") }
                // 由热点名判断产品:锁板 KHW-LOCK-Setup → esp32lock,摄像头 ESP32-CAM-Setup → esp32cam
                val type = if (s.deviceApSsid.contains("LOCK", ignoreCase = true)) "esp32lock" else "esp32cam"
                val client = DeviceClient(
                    Device(id = "provision", type = type, host = "192.168.4.1", port = 80, name = "provision")
                )
                val body = buildJsonObject {
                    put("ssid", s.homeSsid)
                    put("password", s.homePassword)
                }
                val resp = client.post("/config", body)
                if (resp.bool("ok") == true) {
                    // 配网成功 = 用户明确要找回设备:清空已删除集合,让被隐藏设备重新可发现
                    store.clearDeleted()
                    // 固件 /config 返回 deviceId+token(契约 §2):保存令牌,否则摄像头
                    // /stream 与锁板 /action 都会 401(M2 固件鉴权)。
                    val deviceId = resp.str("deviceId")
                    val token = resp.str("token")
                    if (deviceId != null && token != null) {
                        saveTokenForDevice(type, deviceId, token)
                    }
                    _state.update { it.copy(busy = false, done = true, message = "配网成功,设备正在重启…") }
                } else {
                    _state.update { it.copy(busy = false, error = "设备返回失败: $resp") }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = "配网失败: ${e.message}(若设备已重启,可直接返回首页等待发现)",
                    )
                }
            } finally {
                connector.release()
            }
        }
    }

    /** 回到初始状态(再次进入配网页时调用,避免上次 done 残留导致误跳转)。 */
    fun reset() {
        stopBleScan()
        _state.value = ProvisionState()
        _wifi.value = WifiScanState()
    }

    override fun onCleared() {
        stopBleScan()
        autoRescanJob?.cancel()
        if (receiverRegistered) {
            try {
                app.unregisterReceiver(scanReceiver)
            } catch (_: Exception) {
            }
        }
        connector.release()
    }
}
