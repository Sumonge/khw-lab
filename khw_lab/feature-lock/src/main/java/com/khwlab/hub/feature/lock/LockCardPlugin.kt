package com.khwlab.hub.feature.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khwlab.hub.core.model.ConnectionState
import com.khwlab.hub.core.model.Device
import com.khwlab.hub.core.net.DeviceClient
import com.khwlab.hub.core.plugin.CardPlugin

/**
 * 电磁锁插件(产品二,XIAO ESP32-C3 锁板)。
 * type 与 mDNS 服务类型 _esp32lock._tcp 一一对应,宿主按 type 路由到本插件。
 */
class LockCardPlugin : CardPlugin {
    override val type: String = "esp32lock"
    override val displayName: String = "电磁锁"
    override val icon: ImageVector = Icons.Filled.Lock

    @Composable
    override fun Tile(device: Device, state: ConnectionState) {
        LockTile(device, state)
    }

    @Composable
    override fun Detail(device: Device, client: DeviceClient) {
        LockDetailScreen(device, client)
    }

    @Composable
    override fun Settings(device: Device, client: DeviceClient) {
        LockSettingsScreen(device, client)
    }
}

@Composable
private fun LockTile(device: Device, state: ConnectionState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            device.name,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        StatusDot(state)
    }
}

@Composable
private fun StatusDot(state: ConnectionState) {
    val (color, label) = when (state) {
        ConnectionState.ONLINE -> Color(0xFF4CAF50) to "在线"
        ConnectionState.CONNECTING -> Color(0xFFFFC107) to "连接中"
        ConnectionState.OFFLINE -> Color(0xFF9E9E9E) to "离线"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
