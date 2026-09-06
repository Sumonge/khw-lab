package com.khwlab.hub.device

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.khwlab.hub.core.model.Device
import com.khwlab.hub.core.net.DeviceClient
import com.khwlab.hub.core.plugin.CardRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingsScreen(
    device: Device,
    registry: CardRegistry,
    client: DeviceClient,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val plugin = registry[device.type]

    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 重命名成功后当前页标题与设备名立刻更新,不必退回首页才看到
    var displayName by remember(device.id) { mutableStateOf(device.name) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("$displayName 设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("设备信息", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("名称: $displayName", style = MaterialTheme.typography.bodyMedium)
            Text("ID: ${device.id}", style = MaterialTheme.typography.bodyMedium)
            Text("类型: ${device.type}", style = MaterialTheme.typography.bodyMedium)
            Text("地址: ${device.host}:${device.port}", style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showRename = true }) {
                    Text("重命名")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = null, Modifier.width(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除设备", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("类型专属设置", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (plugin != null) {
                plugin.Settings(device, client)
            } else {
                Text("该类型暂无设置", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (showRename) {
        RenameDialog(
            currentName = displayName,
            onConfirm = { newName ->
                displayName = newName
                onRename(newName)
                showRename = false
                Toast.makeText(context, "已重命名为「$newName」", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showRename = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除设备") },
            text = { Text("删除后首页不再显示,重新配网可找回。确定删除 \"$displayName\" 吗?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("显示名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
