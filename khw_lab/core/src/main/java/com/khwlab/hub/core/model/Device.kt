package com.khwlab.hub.core.model

/**
 * 一台设备的通用抽象。不含任何设备专属字段。
 * id 为主机名(如 "esp32cam-d5b748"),type 从 mDNS 服务类型解析(如 "esp32cam")。
 */
data class Device(
    val id: String,
    val type: String,
    val host: String,
    val port: Int,
    val name: String,
) {
    val baseUrl: String get() = "http://$host:$port"
}
