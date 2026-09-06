package com.khwlab.hub.provision

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvisionScreen(
    onDone: () -> Unit,
    onRequestWifiPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    vm: ProvisionViewModel = viewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val w by vm.wifi.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.startScan() }

    // 配网成功(固件已确认并重启)后自动返回首页等待发现,不用再点按钮
    LaunchedEffect(s.done) {
        if (s.done) onDone()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("配网") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            Text("设备热点", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = s.deviceApSsid,
                onValueChange = vm::setDeviceApSsid,
                label = { Text("设备热点名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "默认是设备开机热点 \"$DEVICE_AP_SSID\",一般不用改。",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(20.dp))
            Text("家庭 WiFi", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = s.homeSsid,
                onValueChange = vm::setHomeSsid,
                label = { Text("家庭 WiFi 名称 (SSID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = s.homePassword,
                onValueChange = vm::setHomePassword,
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { vm.openWifiPicker(onRequestWifiPermissions) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("扫描附近网络")
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { vm.provision() },
                enabled = !s.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (s.busy) "配网中…" else "连接设备并配网")
            }

            s.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            s.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (w.pickerOpen) {
        WifiPickerDialog(
            vm = vm,
            wifi = w,
            onRequestPermissions = onRequestWifiPermissions,
            onOpenAppSettings = onOpenAppSettings,
            onDismiss = { vm.closeWifiPicker() },
        )
    }
}

/** 选择附近网络的浮窗:滑条选择 → 确定 → 填入 SSID。 */
@Composable
private fun WifiPickerDialog(
    vm: ProvisionViewModel,
    wifi: WifiScanState,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("选择附近 WiFi", style = MaterialTheme.typography.titleLarge)

                Spacer(Modifier.height(12.dp))

                if (wifi.ssids.isEmpty()) {
                    Text(
                        if (wifi.scanning) "正在扫描附近网络…" else "还没有扫到 WiFi",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    wifi.error?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.weight(1f)) {
                                Text(if (Build.VERSION.SDK_INT >= 33) "去授权「附近设备」" else "去授权「位置」")
                            }
                            // 权限弹窗被系统压掉(之前拒绝过)时的兜底:直达应用权限页
                            OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.weight(1f)) {
                                Text("去系统设置开启")
                            }
                        }
                    }
                } else if (wifi.ssids.size == 1) {
                    // 只扫到一个:无需列表,直接展示并选中(避免空列表/除零)
                    Text(
                        wifi.ssids.first(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                } else {
                    // 点选式列表:和系统 WiFi 选择一致,点哪行选哪个,选中行打勾
                    Column(modifier = Modifier.heightIn(max = 280.dp)) {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(wifi.ssids, key = { it }) { ssid ->
                                val selected = ssid == wifi.selectedSsid
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.selectSsid(ssid) }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        ssid,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (selected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "已选中",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                androidx.compose.material3.HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                }

                if (wifi.ssids.isNotEmpty()) {
                    wifi.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = { vm.startScan() }, enabled = !wifi.scanning) {
                        Text(if (wifi.scanning) "扫描中…" else "刷新")
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    // 只有一个 WiFi 时直接用它,否则用滑条选中的
                    val confirmSsid = wifi.selectedSsid.ifEmpty { wifi.ssids.singleOrNull() }
                    Button(
                        onClick = {
                            confirmSsid?.let {
                                vm.selectSsid(it)
                                onDismiss()
                            }
                        },
                        enabled = confirmSsid != null,
                    ) {
                        Text("确定")
                    }
                }
            }
        }
    }
}
