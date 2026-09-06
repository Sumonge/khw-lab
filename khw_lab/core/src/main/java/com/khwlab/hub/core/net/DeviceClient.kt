package com.khwlab.hub.core.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.khwlab.hub.core.model.ConnectionState
import com.khwlab.hub.core.model.Device
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 所有插件共用的设备 I/O 基座。一个 Device 对应一个实例(由宿主缓存复用,
 * 见 HomeViewModel.clientFor:同 id 恒返回同一实例,probe/stream/settings 共享
 * [connection] 状态与已建立连接,避免各页面各自新建导致重复连接与超时)。
 */
class DeviceClient(private val device: Device) {

    /** 客户端是否仍对应当前设备地址与类型(host/port/type 未变时复用,变了需重建)。 */
    fun matches(d: Device): Boolean =
        device.host == d.host && device.port == d.port && device.type == d.type

    // 短请求(get/post/探测),带超时
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // 流式客户端(MJPEG 长连接)。读超时 5s:固件卡死/停流时能断开重连,
    // 避免无限阻塞导致 "在线" 假象(帧率通常 5-15fps,5s 足够)
    private val streamHttp = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _connection = MutableStateFlow(ConnectionState.OFFLINE)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(path: String): JsonElement = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(device.baseUrl + path).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            try {
                json.parseToJsonElement(resp.body?.string() ?: "{}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 解析/URL 等非 IO 异常统一转成受检 IOException,避免上层协程被未捕获异常崩掉
                throw IOException("响应解析失败", e)
            }
        }
    }

    suspend fun post(path: String, body: JsonElement): JsonElement = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(device.baseUrl + path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            try {
                json.parseToJsonElement(resp.body?.string() ?: "{}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 解析/URL 等非 IO 异常统一转成受检 IOException,避免上层协程被未捕获异常崩掉
                throw IOException("响应解析失败", e)
            }
        }
    }

    /** 可达性探测(周期心跳),返回是否在线。 */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(device.baseUrl + "/status").get().build()
            http.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                _connection.value = if (ok) ConnectionState.ONLINE else ConnectionState.OFFLINE
                ok
            }
        } catch (e: IOException) {
            _connection.value = ConnectionState.OFFLINE
            false
        }
    }

    /**
     * MJPEG 帧流。断线自动重连,直到收集方取消。
     * 固件 /stream 返回 multipart/x-mixed-replace,边界行格式为 `--<boundary>\r\n`。
     */
    fun frameStream(): Flow<Bitmap> = flow {
        while (true) {
            try {
                val req = Request.Builder().url(device.baseUrl + "/stream").get().build()
                _connection.value = ConnectionState.CONNECTING
                val resp = streamHttp.newCall(req).execute()
                if (!resp.isSuccessful) {
                    resp.close()
                    throw IOException("HTTP ${resp.code}")
                }
                resp.use { r ->
                    val body = r.body ?: throw IOException("empty body")
                    val boundary = body.contentType()?.parameter("boundary")
                        ?: throw IOException("no boundary")
                    val source = body.source()
                    val delim = "--$boundary\r\n".encodeUtf8()

                    while (true) {
                        if (!source.skipTo(delim)) break
                        var contentLength = -1
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
                            }
                        }
                        if (contentLength > 0) {
                            val bytes = source.readByteArray(contentLength.toLong())
                            _connection.value = ConnectionState.ONLINE
                            // RGB_565:单帧显存减半(XGA 1.5MB),边解码边 GC 更稳,缓解录像时 OOM
                            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                            if (bmp != null) emit(bmp)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _connection.value = ConnectionState.OFFLINE
            }
            delay(2000) // 断线重连间隔
        }
    }.flowOn(Dispatchers.IO)

    /** 遥测轮询(声量、位置等,摄像头用不到,给后续产品)。 */
    fun poll(path: String, intervalMs: Long): Flow<JsonElement> = flow {
        while (true) {
            emit(get(path))
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)
}

/** 从缓冲里定位并跳过指定字节串,返回是否找到。 */
private fun BufferedSource.skipTo(delim: ByteString): Boolean {
    val idx = indexOf(delim)
    if (idx == -1L) return false
    skip(idx + delim.size)
    return true
}
