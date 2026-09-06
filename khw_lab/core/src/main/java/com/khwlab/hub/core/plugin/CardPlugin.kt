package com.khwlab.hub.core.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.khwlab.hub.core.model.ConnectionState
import com.khwlab.hub.core.model.Device
import com.khwlab.hub.core.net.DeviceClient

/**
 * 卡槽契约(核心交付物)。每个智能产品 = 一个实现本接口的插件模块(:feature-*),
 * 宿主通过 [CardRegistry] 按 type 路由,不感知具体设备。
 *
 * 约定:同一设备的 [Detail] 与 [Settings] 必须接收同一个 [DeviceClient] ——
 * 宿主在进入详情/设置前已经为它建好了客户端(probe 心跳),插件不要再 `remember { DeviceClient(device) }`
 * 新建,否则等于第二条连接,离线时多一次 connect/read 超时,还各自维护独立的在线状态。
 */
interface CardPlugin {
    /** 唯一标识,和 mDNS 服务类型一一对应(如 "esp32cam")。 */
    val type: String
    val displayName: String
    val icon: ImageVector

    /** 首页小卡。宿主负责外层 Card + 点击,这里只渲染卡片内容。 */
    @Composable fun Tile(device: Device, state: ConnectionState)

    /** 点进去的全屏页。 */
    @Composable fun Detail(device: Device, client: DeviceClient)

    /** 类型专属设置(通用设置由宿主统一提供)。 */
    @Composable fun Settings(device: Device, client: DeviceClient)
}
