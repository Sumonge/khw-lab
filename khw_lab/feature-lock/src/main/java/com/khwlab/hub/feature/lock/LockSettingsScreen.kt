package com.khwlab.hub.feature.lock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.khwlab.hub.core.model.Device
import com.khwlab.hub.core.net.DeviceClient
import com.khwlab.hub.core.net.UnauthorizedException
import com.khwlab.hub.core.net.int
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 电磁锁专属设置:自动回锁时长(自动开锁后 N 秒内无人再操作则自动上锁)。
 * 契约单位:秒(unlockSec, 0 = 关闭自动回锁)。GET /settings 读取, POST /settings 保存。
 * 复用宿主传入的同一 DeviceClient(与详情页共享连接状态),不新建连接;
 * 设备离线/401 显示错误 + 重试,绝不抛异常导致闪退。
 */
@Composable
fun LockSettingsScreen(device: Device, client: DeviceClient) {
    val scope = rememberCoroutineScope()

    var unlockSec by remember { mutableStateOf(0) } // 秒, 0=关闭
    var input by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }

    fun save() {
        scope.launch {
            saving = true
            error = null
            try {
                val sec = input.toIntOrNull() ?: 0
                unlockSec = sec.coerceAtLeast(0)
                val body = buildJsonObject { put("unlockSec", unlockSec) }
                client.post("/settings", body)
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                error = "设备未配对或令牌失效, 请删除设备后重新配网"
            } catch (e: Exception) {
                error = "保存失败:设备未连接"
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(device.id, retryKey) {
        try {
            val s = client.get("/settings")
            unlockSec = s.int("unlockSec") ?: unlockSec
            input = unlockSec.toString()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            error = "设备未配对或令牌失效, 请删除设备后重新配网"
        } catch (e: Exception) {
            error = "设备未连接,无法读取设置"
        } finally {
            loaded = true
        }
    }

    Column(Modifier.padding(16.dp)) {
        Text("电磁锁设置", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        if (!loaded) {
            Text("读取中…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { retryKey++ }) {
                Text("重试")
            }
            return@Column
        }

        Text(
            "自动回锁:开锁后超过设定时长无人再操作,设备自动重新上锁。\n" +
                "设为 0 关闭(默认:断电即开锁,不自动回锁)。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("自动回锁时长(秒)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { save() },
                enabled = !saving,
            ) {
                Text(if (saving) "保存中…" else "保存")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "当前:${unlockSec} 秒" + if (unlockSec > 0) "(开锁后 ${unlockSec} 秒自动锁定)" else "(已关闭)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(12.dp))
        Text(
            "重命名与删除设备请在通用设置中进行。",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(12.dp))
        Text(
            "设备 ID: ${device.id}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
