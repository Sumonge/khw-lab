package com.khwlab.hub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.khwlab.hub.device.DeviceDetailScreen
import com.khwlab.hub.device.DeviceSettingsScreen
import com.khwlab.hub.home.HomeScreen
import com.khwlab.hub.home.HomeViewModel
import com.khwlab.hub.provision.ProvisionScreen
import com.khwlab.hub.provision.ProvisionViewModel
import com.khwlab.hub.ui.theme.HubTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LabHub"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // 授权后由各 VM 的 onPermissionHandled 决定是否开扫;这里只是兜底
        Log.d(TAG, "discovery permission result: $it")
    }

    // 配网页"扫描附近网络"的授权:同意后自动开扫,拒绝则浮窗给出明确原因
    private val provisionVm: ProvisionViewModel by viewModels()
    private val pickerPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 以 VM 实际权限为准(33+ 需要 NEARBY 且要 LOCATION,缺一不可);
        // 只要请求过一次,VM 就知道不能再靠弹框拿权限了
        provisionVm.onPermissionHandled(result.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestDiscoveryPermission()

        setContent {
            HubTheme {
                val vm: MainViewModel = viewModel()
                val homeVm: HomeViewModel = viewModel()
                val screen by vm.screen.collectAsStateWithLifecycle()
                val registry = (application as App).registry

                if (screen !is Screen.Home) {
                    BackHandler { vm.back() }
                }

                when (val s = screen) {
                    is Screen.Home -> HomeScreen(
                        registry = registry,
                        onAdd = { vm.navigate(Screen.Provision) },
                        onOpen = { vm.navigate(Screen.Detail(it)) },
                    )
                    is Screen.Provision -> {
                        LaunchedEffect(Unit) { provisionVm.reset() }
                        ProvisionScreen(
                            onDone = { vm.back() },
                            onRequestWifiPermissions = {
                                // 33+:WiFi 扫描结果列表同时要 NEARBY + LOCATION,
                                // 两个一起请求(缺一不可);旧版只需位置。
                                val perms = if (Build.VERSION.SDK_INT >= 33) {
                                    arrayOf(
                                        Manifest.permission.NEARBY_WIFI_DEVICES,
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                    )
                                } else {
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                                val missing = perms.filter {
                                    ContextCompat.checkSelfPermission(this, it) !=
                                        PackageManager.PERMISSION_GRANTED
                                }
                                if (missing.isEmpty()) {
                                    // 已全授权(可能刚从设置页返回):直接开扫
                                    provisionVm.onPermissionHandled(true)
                                } else if (provisionVm.hasRequestedPermission()) {
                                    // 上次请求过但没给 → 系统已记录"拒绝",再 launch 大概率静默不弹,
                                    // 直接引导去系统设置手动开启(清除数据不会重置权限)。
                                    openAppSettings()
                                } else {
                                    // 首次请求:弹系统授权框,授权结果走 pickerPermissionLauncher
                                    pickerPermissionLauncher.launch(missing.toTypedArray())
                                }
                            },
                            onOpenAppSettings = { openAppSettings() },
                            vm = provisionVm,
                        )
                    }
                    is Screen.Detail -> DeviceDetailScreen(
                        device = s.device,
                        registry = registry,
                        client = homeVm.clientFor(s.device),
                        onBack = { vm.back() },
                        onSettings = { vm.navigate(Screen.Settings(s.device)) },
                    )
                    is Screen.Settings -> DeviceSettingsScreen(
                        device = s.device,
                        registry = registry,
                        client = homeVm.clientFor(s.device),
                        onBack = { vm.back() },
                        onRename = { newName ->
                            homeVm.rename(s.device.id, newName)
                            // 同步导航栈里的设备快照,退出设置页重进仍是新名
                            vm.updateDevice(s.device.id, newName)
                        },
                        onDelete = {
                            homeVm.delete(s.device.id)
                            vm.goHome()
                        },
                    )
                }
            }
        }
    }

    /** 直达本应用的系统设置页(权限被永久拒绝后唯一的手动开启路径)。 */
    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                )
            )
        } catch (_: Exception) {
        }
    }

    /** mDNS(NsdManager)与 WiFi 扫描所需权限,33+ 用 NEARBY_WIFI_DEVICES + 位置,旧版用定位。
     * 注意:若用户此前点过"拒绝",系统之后不会再弹框,只能去系统设置开启 —— UI 里给了入口。 */
    private fun requestDiscoveryPermission() {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
