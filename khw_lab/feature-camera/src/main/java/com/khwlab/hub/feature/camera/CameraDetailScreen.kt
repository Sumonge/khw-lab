package com.khwlab.hub.feature.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.khwlab.hub.core.model.ConnectionState
import com.khwlab.hub.core.model.Device
import com.khwlab.hub.core.net.DeviceClient
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/**
 * 摄像头全屏页:单一 /stream 连接 → [SharingStarted.WhileSubscribed] 共享流。
 * 视频帧仅此一条通道(截图/录像都从它取,避免固件单线程 WebServer 被双连接互踢)。
 * 底部控制栏:截图 / 录像。
 */
@Composable
fun CameraDetailScreen(device: Device, client: DeviceClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recordingVm: RecordingViewModel = viewModel()

    // 复用宿主传入的同一 DeviceClient(与设置页共享连接状态,不新建第二条连接);
    // 单条 MJPEG 流 shareIn,record 启动时(及其后 5s 保活窗口)才有订阅者
    val frames = remember(device.id) {
        client.frameStream()
            .shareIn(scope, SharingStarted.WhileSubscribed(5000), replay = 0)
    }
    val state by client.connection.collectAsStateWithLifecycle()
    val recording by recordingVm.recording.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }

    // 预览:最新帧
    var previewFrame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(device.id) {
        frames.collect { bmp -> previewFrame = bmp }
    }

    // 录制同步:同一帧流喂给录像 VM(单一数据源)
    LaunchedEffect(device.id) {
        frames.collect { bmp -> recordingVm.processFrame(bmp) }
    }

    // 录像结束消息 → Snackbar
    LaunchedEffect(recordingVm.message) {
        recordingVm.message.collect { msg ->
            if (msg != null) {
                snackbar.showSnackbar(msg)
                recordingVm.clearMessage()
            }
        }
    }

    // 生命周期:ON_STOP(退后台/离开页面)或 composition 销毁时,若在录则自动停止收尾。
    // RecordingViewModel 是 Activity 级(compose viewModel()),不随本页销毁,必须显式停。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) recordingVm.stopRecording()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            recordingVm.stopRecording()
        }
    }

    // 截图权限(仅 API 28- 需要 WRITE_EXTERNAL_STORAGE;29+ 走 MediaStore 免权限)
    // pendingAction:授权后继续之前要做的操作(null=无 / capture=截图 / record=录像)
    var pendingAction by remember { mutableStateOf<String?>(null) }
    val writePerm = Manifest.permission.WRITE_EXTERNAL_STORAGE
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when (pendingAction) {
                "capture" -> {
                    val bmp = previewFrame
                    if (bmp != null) {
                        scope.launch {
                            try {
                                ScreenshotSaver.save(context, bmp)
                                snackbar.showSnackbar("截图已保存")
                            } catch (e: Exception) {
                                snackbar.showSnackbar("截图失败: ${e.message}")
                            }
                        }
                    }
                }
                "record" -> recordingVm.startRecording()
                else -> {}
            }
        } else {
            scope.launch {
                snackbar.showSnackbar(
                    if (pendingAction == "record") "需要存储权限才能录像" else "需要存储权限才能截图"
                )
            }
        }
        pendingAction = null
    }

    /** API 28- 需要存储权限:无权限先请求,有权限直接执行。 */
    fun <T> requireStoragePermission(action: String, run: () -> T) {
        if (Build.VERSION.SDK_INT >= 29) {
            run()
        } else if (ContextCompat.checkSelfPermission(context, writePerm) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            run()
        } else {
            pendingAction = action
            storageLauncher.launch(writePerm)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val bitmap = previewFrame
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "camera stream",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (state == ConnectionState.OFFLINE) "已断开,重连中…" else "连接中…",
                    color = Color.White,
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp), // 抬到控制栏上方,避免重叠
        )

        ControlBar(
            recording = recording,
            onCapture = {
                val bmp = previewFrame
                if (bmp == null) {
                    scope.launch { snackbar.showSnackbar("暂无画面,无法截图") }
                } else {
                    requireStoragePermission("capture") {
                        scope.launch {
                            try {
                                ScreenshotSaver.save(context, bmp)
                                snackbar.showSnackbar("截图已保存")
                            } catch (e: Exception) {
                                snackbar.showSnackbar("截图失败: ${e.message}")
                            }
                        }
                    }
                }
            },
            onRecord = {
                if (recordingVm.recording.value) {
                    recordingVm.stopRecording()
                } else {
                    requireStoragePermission("record") {
                        recordingVm.startRecording()
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun ControlBar(
    recording: Boolean,
    onCapture: () -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TextButton(onClick = onCapture) {
            Icon(
                Icons.Filled.CameraAlt,
                contentDescription = "截图",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("截图", color = Color.White)
        }
        TextButton(onClick = onRecord) {
            Icon(
                if (recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                contentDescription = if (recording) "停止录像" else "开始录像",
                tint = if (recording) Color(0xFFF44336) else Color.White,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(if (recording) "停止" else "录像", color = Color.White)
        }
    }
}
