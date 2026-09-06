package com.khwlab.hub.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.khwlab.hub.core.model.Device
import com.khwlab.hub.core.plugin.CardRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    registry: CardRegistry,
    onAdd: () -> Unit,
    onOpen: (Device) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val devices by vm.devices.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Lab Hub") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "配网")
            }
        },
    ) { padding ->
        if (devices.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("还没有设备", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("点右下角 + 给 ESP32-CAM 配网", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(devices, key = { it.id }) { device ->
                    val client = vm.clientFor(device)
                    val state by client.connection.collectAsStateWithLifecycle()
                    val plugin = registry[device.type]
                    Card(onClick = { onOpen(device) }) {
                        if (plugin != null) {
                            plugin.Tile(device, state)
                        } else {
                            Column(Modifier.padding(16.dp)) {
                                Text(device.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "未知类型: ${device.type}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
