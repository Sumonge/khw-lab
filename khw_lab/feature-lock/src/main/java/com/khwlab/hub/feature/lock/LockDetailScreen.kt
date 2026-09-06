package com.khwlab.hub.feature.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khwlab.hub.core.model.ConnectionState
import com.khwlab.hub.core.model.Device
import com.khwlab.hub.core.net.DeviceClient
import com.khwlab.hub.core.net.int
import com.khwlab.hub.core.net.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 电磁锁详情页:大锁按钮(toggle)+ lock/unlock + WS 状态实时推送。
 * 复用宿主传入的同一 DeviceClient(与设置页共享连接状态,不新建连接)。
 *
 * 状态解析防御两种结构(契约 §4 与固件实现不一致,已列入改进清单):
 *   - 动作响应(顶层):{"ok":true,"state":"locked","since":<ms>}
 *   - WS push(包 data):{"type":"status","data":{"ok":true,"state":"...","since":...}}
 * since 为设备启动毫秒(非 epoch),仅展示,不做换算。
 * 设备离线/固件异常一律显示错误 + 可重试,绝不抛异常闪退。
 */
@Composable
fun LockDetailScreen(device: Device, client: DeviceClient) {
    val scope = rememberCoroutineScope()
    val connection by client.connection.collectAsStateWithLifecycle()

    var lockState by remember { mutableStateOf<String?>(null) } // "locked"|"unlocked"|null=未知
    var since by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }

    // 动作响应与 WS push 统一入口:data 内优先,顶层兜底
    fun applyStatus(el: kotlinx.serialization.json.JsonElement?) {
        val obj = el as? JsonObject ?: return
        (obj["data"] as? JsonObject)?.let { data ->
            data.str("state")?.let { lockState = it }
            data.int("since")?.let { since = it.toLong() }
        }
        obj.str("state")?.let { lockState = it }
        obj.int("since")?.let { since = it.toLong() }
    }

    fun send(name: String) {
        scope.launch {
            busy = true
            error = null
            try {
                applyStatus(client.action(name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "操作失败:设备未连接"
            } finally {
                busy = false
            }
        }
    }

    // 进页拉一次当前状态(离线会走 error 分支)
    LaunchedEffect(device.id, retryKey) { send("status") }

    // WS 状态推送:hello/ping 忽略,status 实时刷新锁状态(未配对 M1 无 token 时为空流,不闪退)
    LaunchedEffect(device.id) {
        client.ws().collect { raw ->
            try {
                val el = Json { ignoreUnknownKeys = true }.parseToJsonElement(raw)
                if (el.str("type") == "status") applyStatus(el)
            } catch (_: Exception) {
                // 非 JSON 帧忽略
            }
        }
    }

    val locked = lockState == "locked"
    val unknown = lockState == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = if (locked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                unknown -> "状态未知"
                locked -> "已锁定"
                else -> "已开锁"
            },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        since?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "状态变更于设备运行 ${it / 1000} 秒",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (connection == ConnectionState.OFFLINE) {
            Spacer(Modifier.height(16.dp))
            Text("设备离线,重连中…", style = MaterialTheme.typography.bodyMedium)
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { retryKey++ }) { Text("重试") }
        }

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { send("toggle") },
            enabled = !busy && !unknown,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                when {
                    busy -> "处理中…"
                    unknown -> "读取状态…"
                    locked -> "开锁"
                    else -> "锁定"
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            OutlinedButton(onClick = { send("lock") }, enabled = !busy) { Text("锁定") }
            OutlinedButton(onClick = { send("unlock") }, enabled = !busy) { Text("开锁") }
        }
    }
}
