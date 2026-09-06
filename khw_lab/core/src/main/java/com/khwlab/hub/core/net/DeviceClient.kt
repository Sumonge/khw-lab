package com.khwlab.hub.core.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.khwlab.hub.core.model.ConnectionState
import com.khwlab.hub.core.model.Device
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 所有插件共用的设备 I/O 基座。一个 Device 对应一个实例(由宿主缓存复用,
 * 见 HomeViewModel.clientFor:同 id 恒返回同一实例,probe/stream/settings 共享
 * [connection] 状态与已建立连接,避免各页面各自新建导致重复连接与超时)。
 *
 * [token] 为配网成功后固件下发的共享令牌(契约 §2)。M2 起 /action、/unpair、
 * /stream 与 WebSocket 都要求 X-Khw-Token;M1 存量设备(未配对过)传 null:
 * 请求不带令牌头,ws() 返回空流(固件 0.3.0 起 /stream 无令牌会 401)。
 */
class DeviceClient(
    private val device: Device,
    val token: String? = null,
) {

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

    /** 有令牌时给请求加 X-Khw-Token 头(M2 鉴权,契约 §2)。 */
    private fun Request.Builder.auth(): Request.Builder = apply {
        token?.let { header("X-Khw-Token", it) }
    }

    suspend fun get(path: String): JsonElement = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(device.baseUrl + path).get().auth().build()
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
            .auth()
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

    /**
     * 动作总线(契约 §4):POST /action,body {"action":"<name>","params":{...}}。
     * 锁板:lock/unlock/toggle/status;摄像头板:status/reboot。
     * 未配对设备(M1 存量,无 token)会收 401。
     */
    suspend fun action(name: String, params: JsonObject = buildJsonObject { }): JsonElement =
        post("/action", buildJsonObject {
            put("action", name)
            put("params", params)
        })

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
     * 0.3.0 起 /stream 需令牌(否则 401),M1 存量设备(无 token)会一直重连。
     */
    fun frameStream(): Flow<Bitmap> = flow {
        while (true) {
            try {
                val req = Request.Builder().url(device.baseUrl + "/stream").get().auth().build()
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

    /**
     * WebSocket 推送流(契约 §5 §9):连 ws://host:8080/ws?token=,逐条透传设备
     * push 的原始 JSON(hello/ping/status)。断线每 2s 重连,直到收集方取消。
     * 未配过网(M1 存量,无 token)返回空流:固件对无令牌握手回 401,没有连的必要。
     * 注意:必须关读超时——设备 hello 后只靠 30s ping 维持,10s 默认读超时会把
     * 连接砍断导致无限重连。
     */
    fun ws(): Flow<String> {
        val t = token ?: return emptyFlow()
        return flow {
            while (true) {
                val queue = Channel<String>(Channel.UNLIMITED)
                var webSocket: WebSocket? = null
                val wsHttp = OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
                try {
                    val listener = object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            queue.trySend(text)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            queue.close()
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            queue.close()
                        }
                    }
                    webSocket = wsHttp.newWebSocket(
                        Request.Builder().url("ws://${device.host}:8080/ws?token=$t").build(),
                        listener
                    )
                    // 阻塞收集,直到连接关闭/失败;设备 push 逐条 emit
                    for (msg in queue) emit(msg)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 连接异常:等 2s 重连
                } finally {
                    webSocket?.close(1000, "client bye")
                    wsHttp.connectionPool.evictAll()
                    wsHttp.dispatcher.executorService.shutdown()
                }
                delay(2000)
            }
        }.flowOn(Dispatchers.IO)
    }

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
