package com.khwlab.hub.feature.camera

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.khwlab.hub.core.net.bool
import com.khwlab.hub.core.net.int
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 摄像头专属设置:分辨率/画质/翻转/镜像,GET /settings 读取,POST /settings 应用。
 * 复用宿主传入的同一 DeviceClient(与详情页共享连接状态),不新建第二条连接;
 * 设备离线时显示错误 + 重试,绝不抛异常导致闪退。
 */
@Composable
fun CameraSettingsScreen(device: Device, client: DeviceClient) {
    val scope = rememberCoroutineScope()

    var resolution by remember { mutableStateOf(CameraResolution.XGA.framesize) }
    var quality by remember { mutableStateOf(12) }
    var vflip by remember { mutableStateOf(false) }
    var hmirror by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }

    fun apply() {
        scope.launch {
            saving = true
            error = null
            try {
                val body = buildJsonObject {
                    put("resolution", resolution)
                    put("quality", quality)
                    put("vflip", vflip)
                    put("hmirror", hmirror)
                }
                client.post("/settings", body)
            } catch (e: CancellationException) {
                throw e
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
            resolution = s.int("resolution") ?: resolution
            quality = s.int("quality") ?: quality
            vflip = s.bool("vflip") ?: vflip
            hmirror = s.bool("hmirror") ?: hmirror
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "设备未连接,无法读取设置"
        } finally {
            loaded = true
        }
    }

    // 宿主 DeviceSettingsScreen 的外层 Column 已 verticalScroll,这里只渲染内容,
    // 不再套滚动/铺满 —— 内层 fillMaxSize+verticalScroll 会形成无限高度约束导致崩溃
    Column(
        Modifier
            .padding(16.dp)
    ) {
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

        Text("分辨率", style = MaterialTheme.typography.titleSmall)
        CameraResolution.entries.forEach { r ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { resolution = r.framesize }
                    .padding(vertical = 2.dp),
            ) {
                RadioButton(
                    selected = resolution == r.framesize,
                    onClick = { resolution = r.framesize },
                )
                Text(r.label, Modifier.padding(start = 4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("画质 (数值越小越清晰): $quality", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = quality.toFloat(),
            onValueChange = { quality = it.toInt() },
            valueRange = 4f..63f,
        )

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("垂直翻转", Modifier.weight(1f))
            Switch(checked = vflip, onCheckedChange = { vflip = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("水平镜像", Modifier.weight(1f))
            Switch(checked = hmirror, onCheckedChange = { hmirror = it })
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { apply() },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saving) "保存中…" else "保存并应用")
        }
    }
}
