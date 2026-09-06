package com.khwlab.hub.feature.lock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.khwlab.hub.core.model.Device
import com.khwlab.hub.core.net.DeviceClient

/**
 * 电磁锁专属设置。
 * 注意:锁板固件(board-lock.ino)当前只注册了 /status /config /action /unpair,
 * 没有板级 /settings —— GET /settings 会 404。且 3V 锁无状态反馈,可配置项为零,
 * 因此这里仅展示说明文字,不做网络请求,也不展示"保存"按钮。
 * (默认动作/开锁时常等后续若固件增加 /settings 端点,再在这里实现。)
 */
@Composable
fun LockSettingsScreen(device: Device, client: DeviceClient) {
    Column(Modifier.padding(16.dp)) {
        Text("电磁锁设置", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        Text(
            "当前固件版本无专属设置项(锁板为纯开关控制,3V 电磁锁无状态反馈)。\n\n" +
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
