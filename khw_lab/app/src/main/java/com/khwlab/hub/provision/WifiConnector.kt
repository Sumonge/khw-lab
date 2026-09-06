package com.khwlab.hub.provision

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** 用 WifiNetworkSpecifier 临时连到设备热点(API 29+),配网完成后 release。 */
class WifiConnector(private val context: Context) {

    /** 等系统回调(找到热点弹确认框 / 找不到)的最长时间;超时按失败处理,避免无限「配网中…」。 */
    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
    }

    private val cm =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    suspend fun connectToAp(ssid: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false // 低版本走手动引导
        return try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val specifier = WifiNetworkSpecifier.Builder()
                        .setSsid(ssid)
                        .build()
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .setNetworkSpecifier(specifier)
                        .build()

                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            cm.bindProcessToNetwork(network)
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onUnavailable() {
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                    activeCallback = callback
                    cm.requestNetwork(request, callback)
                    cont.invokeOnCancellation {
                        try {
                            cm.unregisterNetworkCallback(callback)
                        } catch (_: Exception) {
                        }
                        activeCallback = null
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            release() // 超时:注销回调并解绑(幂等),按失败返回
            false
        }
    }

    /** 解绑网络,让手机回到默认 WiFi。 */
    fun release() {
        try {
            activeCallback?.let { cm.unregisterNetworkCallback(it) }
            activeCallback = null
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
    }
}
