package com.khwlab.hub.feature.camera

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * 录像控制器(AndroidViewModel 生命周期稳定,配置变化不丢状态)。
 *
 * 单一数据源约定:帧由宿主页(CameraDetailScreen)的唯一 /stream 连接采集,
 * 通过 [processFrame] 喂进来(UI 线程);本类只负责编码 + 落盘,不自己拉流,
 * 避免同一设备上出现第二条 MJPEG 连接(固件 WebServer 单线程,双连接会互踢)。
 *
 * 无音频(板子无麦克风,见 PLAN §5.7)。
 */
class RecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _framesRecorded = MutableStateFlow(0)
    val framesRecorded: StateFlow<Int> = _framesRecorded.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Snackbar 显示后清除。 */
    fun clearMessage() {
        _message.update { null }
    }

    private val latestFrame = MutableStateFlow<Bitmap?>(null)

    private var recorder: MjpegVideoRecorder? = null
    private var tempFile: File? = null
    private var encoderJob: Job? = null

    /** 宿主每来一帧调用一次(UI 线程),录制中会进编码器。 */
    fun processFrame(bmp: Bitmap) {
        latestFrame.update { bmp }
    }

    fun startRecording() {
        if (_recording.value) return
        _recording.update { true }
        _framesRecorded.update { 0 }
        _message.update { null }

        viewModelScope.launch {
            // 拿首帧尺寸初始化编码器(首帧最多等 5s,取不到就放弃:没画面录了也没意义)
            var frame = latestFrame.value
            if (frame == null) {
                repeat(50) {
                    frame = latestFrame.value
                    if (frame != null) return@repeat
                    delay(100)
                }
            }
            if (frame == null) {
                _recording.update { false }
                _message.update { "无画面,无法录像" }
                return@launch
            }
            val w = frame.width
            val h = frame.height

            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "khwlab_rec_$ts.mp4"
            val f = File(app.cacheDir, name)
            tempFile = f
            val r = MjpegVideoRecorder(f, w, h)
            try {
                withContext(Dispatchers.IO) { r.start() }
            } catch (e: Exception) {
                _recording.update { false }
                _message.update { "录像启动失败: ${e.message}" }
                return@launch
            }
            recorder = r

            // 编码协程:轮询最新帧喂给编码器(IO 线程)
            encoderJob = launch(Dispatchers.IO) {
                while (coroutineContext.isActive && _recording.value) {
                    val bmp = latestFrame.value
                    if (bmp != null) {
                        val rec = recorder
                        if (rec == null) break
                        try {
                            rec.addFrame(bmp)
                            _framesRecorded.update { it + 1 }
                        } catch (_: Exception) {
                            // 编码错误不致命,stop 时会清理半成品
                        }
                    }
                    delay(16) // ~60Hz 轮询,实际帧率由摄像头决定
                }
            }
        }
    }

    fun stopRecording() {
        if (!_recording.value) return
        _recording.update { false }
        _message.update { null }

        viewModelScope.launch {
            encoderJob?.cancel()
            encoderJob = null

            val r = recorder
            recorder = null
            val f = tempFile
            if (r == null || f == null) {
                _message.update { "录像失败" }
                return@launch
            }

            val ok = withContext(Dispatchers.IO) { r.stop() }
            if (ok) {
                val uri = withContext(Dispatchers.IO) { saveToMediaStore(f) }
                if (uri != null) {
                    _message.update { "已保存: $uri" }
                } else {
                    _message.update { "保存到相册失败" }
                }
            } else {
                _message.update { "录像失败(编码器错误)" }
            }
            withContext(Dispatchers.IO) { f.delete() }
        }
    }

    private fun saveToMediaStore(file: File): Uri? {
        val name = file.name
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/khw_hub")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            try {
                resolver.openOutputStream(uri).use { out ->
                    if (out == null) return null
                    file.inputStream().use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onCleared() {
        encoderJob?.cancel()
        recorder?.stop()
        recorder = null
        tempFile?.delete()
        super.onCleared()
    }
}
