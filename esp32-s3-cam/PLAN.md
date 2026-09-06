# ESP32-S3-CAM 智能产品平台 — 设计计划

> 项目愿景:一块 ESP32-S3-CAM 摄像头起步,Android App(Kotlin)统一查看/控制,后续智能产品以"卡槽形式"逐块接入,最终形成一套**带机器视觉的实验室自动化控制平台**。

---

## 1. 目标与三阶段

| 阶段 | 内容 |
|---|---|
| **M1(现在)** | 摄像头项目:ESP32-S3-CAM 出图,App 能发现、连上、看视频 |
| **M2** | 安全令牌、WebSocket 实时控制、BLE 配网、模式 LED;接入第一个新产品(电磁锁控制系统,Seeed XIAO ESP32-C3) |
| **M3+** | 其余智能产品逐个接入(`lib/common` 固件骨架 + `:feature-*` 插件模块) |

M1 虽只有摄像头一张卡,但**必须**按 `:feature-camera` 实现 `CardPlugin` 来搭 —— 这是卡槽骨架,M1 不搭后面就晚了。

---

## 2. 已锁定决策树

| 分支 | 决策 |
|---|---|
| A 固件框架 | Arduino + PlatformIO + `esp32-camera`,MJPEG over HTTP |
| B1 配网 | App 主导,SoftAP + 自建 HTTP `POST /config` |
| B2 发现 | mDNS(`_esp32cam._tcp`)为主,UDP 广播兜底 |
| C 渲染 | 原生 ImageView + 手写 MJPEG,抽象成 `FrameSource` |
| D App 架构 | Jetpack Compose + MVVM + 多模块(`:core`/`:feature-*`/`:app`) |
| E1 卡槽档位 | L1 代码级插件(契约接口 + 注册表) |
| E2 卡槽契约 | `CardPlugin`(Tile/Detail/Settings)+ `ConnectionState` + HTTP 探测 |
| E·I/O | `DeviceClient` 统一 get/post/stream/poll + JSON 信封 `{ok,data,error}` |
| F 固件 API | 双模式:`/config`(配置态);`/stream` `/status` `/settings`(运行态) |
| 录像/截图 | App 端(MediaCodec→MP4;存当前帧),无音频 |
| 安全 | M1 无鉴权,M2 加共享令牌 |
| 拓扑 | 多块异构 ESP32,一板一产品,同局域网 |
| 固件平台 | 共享 `lib/common` 骨架 + 每板一 `env` |
| 实时控制 | M1 全 HTTP,WebSocket 留 M2(手摇/连续拖动) |

---

## 3. 系统拓扑

多块异构 ESP32,一块 = 一个智能产品,全连同一 WiFi,App 里每块 = 一张卡。

| 智能产品 | 硬件 | 说明 |
|---|---|---|
| 摄像头(已有) | ESP32-S3-CAM ×1(GC2145) | 机器视觉 |
| 电磁锁控制系统(M2 第 1 个) | Seeed XIAO ESP32-C3 ×1(板载 WS2812 RGB) | 3V 电磁锁 ×1(2P XH2.5),MOSFET(AO3400)扩流驱动,预留门磁输入 |
| 运动控制 | ESP32 ×1 + 步进/伺服驱动 | X-Y 平台 + 工具头切换 |
| 灯源 | ESP32 ×1(或并入运动板) | 继电器/MOSFET + PWM 调光 |
| 声量检测 | ESP32 ×1 + I2S 麦克风 | 声级读数 |

> 硬件现实:ESP32-S3-CAM 的 GPIO 大部分被摄像头占光,接不下多路电机/继电器/I2S,故必须多板。步进电机需精确脉冲时序(用 RMT 外设),不宜与摄像头/WiFi 同板。

---

## 4. 固件设计

### 4.1 平台结构

```
firmware/
├─ lib/common/          ← 所有板共用的骨架
│   ├─ 配网状态机(BLE 优先 + SoftAP 兜底)
│   ├─ mDNS 广播(每板广播自己的服务类型)
│   ├─ 模式 LED 状态机(板载 WS2812/GPIO,统一指示)
│   ├─ HTTP 服务器底座 + /status + /settings 读写 + NVS 持久化
│   └─ 统一 JSON 约定 {ok, data, error}
├─ board-camera/        ← ESP32-S3-CAM,实现 /stream(摄像头专属)
├─ board-lock/          ← Seeed XIAO ESP32-C3,电磁锁控制系统,/lock
├─ board-motion/        ← 运动板,/motor /tool
├─ board-light/         ← 灯源板,/light
└─ board-sound/         ← 声量板,/level
```

- 工具链:Arduino(esp32-arduino,一套覆盖 ESP32/S3/C3/S2)+ PlatformIO 多 `env`(一板一环境)。
- M1 只建 `board-camera`,但把配网/mDNS/HTTP/settings 写成可抽取结构,作为 `lib/common` 的种子。

### 4.2 固件 HTTP API(双模式)

```
① 配置模式(无 WiFi 凭据,SoftAP 192.168.4.1)—— 只有配网
   POST /config          body {ssid, password} → 存 NVS → 重启转 STA

② 运行模式(已连局域网,STA)—— 完整 API
   GET  /stream          → MJPEG 视频流(仅摄像头)
   GET  /status          → JSON {id, name, type, fwVersion} ← 兼作 HTTP HEAD 可达性探测
   GET  /settings        → 读当前 {resolution, fps, rotation, quality}
   POST /settings        → 写配置 → 存 NVS + 即时生效
   POST /unpair          → 清 WiFi 凭据 + 重启回配网态(App 删除设备即断网)
```

- `/settings` 直接映射 `esp32-camera` 运行时接口(`set_framesize`/`set_quality`/`set_vflip` 等)。
- M2 起:所有端点校验共享令牌(配网时 App 生成)。

### 4.3 BLE 配网(M2)

```
开机无 WiFi 凭据 → BLE 广播配网服务(每板一个 UUID:
    camera 板 "KHW_CAM",锁板 "KHW_LOCK")
    → App 扫描到 → GATT 连接 → 写 WiFi SSID/密码特征
    → 设备连 WiFi → 成功后 App 切回家庭 WiFi → 正常发现
BLE 连不上(手机太旧/信号差)→ 长按 BOOT 4s 回 SoftAP 兜底
```

- BLE 为配网**首选**路径,SoftAP 降级为兜底(M1 逻辑保留)。
- 配网成功后设备生成**共享令牌** → 存 NVS + 通过 BLE/HTTP 回传 App。

### 4.4 模式 LED 状态机(M2,统一)

- XIAO ESP32-C3:**板载 WS2812 RGB**(GPIO7)。
- ESP32-S3-CAM:板载**无**可控 LED,外接 WS2812 或普通 LED 焊到空闲 GPIO。

| 模式 | 灯效 | 含义 |
|---|---|---|
| 上电自检 | 白快闪 3 次 | 开机自检 |
| 配网就绪 | 蓝呼吸慢闪 | BLE 广播中,等 App |
| 配网中 | 蓝常亮 | BLE 已连接,正在收凭据 |
| 连接 WiFi | 黄快闪 | 凭据已存,正在入网 |
| 在线 | 绿常亮 | 已入网,可控制 |
| 动作 | 橙短闪一次 | 每次锁动作/指令反馈 |
| 异常 | 红慢闪 | 故障 |
| 重置中 | 红快闪 | 长按 BOOT 清凭据重启 |

### 4.5 BLE 配网契约(M2 共享,固件/App 共同依据)

```
服务 UUID(每板一个,广播 + App 扫描过滤):
    摄像头板 (KHW_CAM):  6b687763-0000-1000-8000-00805f9b34fb   ("khw" + 'c')
    锁板     (KHW_LOCK): 6b68776c-0000-1000-8000-00805f9b34fb   ("khw" + 'l')

特征 UUID(两板共用同一组 UUID,服务 UUID 区分产品):
    SSID   (Write)        6b6877d1-0000-1000-8000-00805f9b34fb
    PASS   (Write)        6b6877d2-0000-1000-8000-00805f9b34fb
    STATUS (Read/Notify)  6b6877d3-0000-1000-8000-00805f9b34fb

流程:
  App 扫描到服务 UUID → GATT 连接 → 写 SSID → 写 PASS
  → 设备连 WiFi → STATUS Notify 回 JSON:
      连接中 {"status":"connecting"}
      成功   {"status":"ok","token":"<16hex>","host":"192.168.x.x"} → 设备重启入网
      失败   {"status":"fail","error":"wifi"}
  → App 收到 ok 后保存 token,切回家庭 WiFi 正常发现
BLE 连不上(手机太旧/信号差)→ 长按 BOOT 4s 清凭据重启回配网态(SoftAP 兜底保留,M1 逻辑)
```

### 4.6 共享令牌鉴权(M2)

- 配网成功(无论 BLE 还是 SoftAP)时设备生成 **16 位十六进制令牌**(NVS `sec`/`token`),随配网响应回传 App,App 与设备绑定持久化。
- 设备后续**运行态** HTTP 请求带请求头 `X-Khw-Token: <token>`;WebSocket 用查询参数 `/ws?token=<token>`。
- 校验失败返回 `401 {"ok":false,"error":"unauthorized"}`。
- **例外**:`GET /status` 与 `POST /config`(配网态)保持**免令牌**——`/status` 是发现/探测/健康用的身份端点,不含敏感数据,否则 App 在拿到 token 之前无法发现设备;`/config` 是配网态端点,尚无 token。
- 其余端点(`/stream`、`/settings`、`/action`、`/unpair`、WS)一律校验令牌。
- M1 存量设备(无 token):固件首次入网自动 `ensure()` 生成;App 端旧设备需**重新配网一次**补齐 token 才能控制(文档注明)。

### 4.7 动作总线 POST /action(M2)

```
请求  {"action":"lock","params":{...}}       ← params 板级自定义,可空
响应  {"ok":true,"data":{...}} 或 {"ok":false,"error":"..."}

锁板   action: lock  / unlock / toggle / status   → data.state:"locked"|"unlocked"
摄像头板 action: status(等价 /status)
```

- 每个产品把自己的动作处理器注册进 `lib/common` 动作总线;HTTP 动作走 `POST /action`,实时连续控制走 WS(见 4.8)。

### 4.8 WebSocket 通道(M2)

```
监听端口 8080,路径 /ws,鉴权 ?token=<token>。单客户端,文本帧(JSON)。
  设备→App  push:  {"type":"status","data":{...}}    状态/锁动作变化即推
  App→设备  cmd:   {"type":"action","data":{"action":"lock"}}   (HTTP 够用时可选)
握手: 标准 RFC6455(SHA1 + base64);设备回 ping→pong;单帧消息(FIN=1)。
```

---

## 5. App 设计

### 5.1 模块结构

```
:app                ← 宿主:配网流程 + 发现 + 卡片容器 UI + 注册表
:core               ← Device / CardPlugin / DeviceClient / 发现 / FrameSource
:feature-camera     ← ESP32-CAM 的"卡片"实现(独立模块)
```

以后每加一个产品 = 加一个 `:feature-xxx` 兄弟模块,实现同一 `CardPlugin`。

### 5.2 卡槽契约(核心交付物)

```kotlin
// :core —— 通用抽象,不含任何设备专属字段
data class Device(
    val id: String,    // 主机名,如 "esp32cam-a1b2c3"
    val type: String,  // 从 mDNS 服务类型解析,如 "esp32cam"
    val host: String,  // IP
    val port: Int,
    val name: String   // 用户可改的显示名
)

enum class ConnectionState { CONNECTING, ONLINE, OFFLINE }

interface CardPlugin {
    val type: String          // 唯一,和 mDNS 服务类型一一对应
    val displayName: String
    val icon: ImageVector

    @Composable fun Tile(device: Device, state: ConnectionState)   // 首页小卡,离线置灰
    @Composable fun Detail(device: Device, state: ConnectionState) // 点进去的全屏页
    @Composable fun Settings(device: Device)                       // 类型专属设置
}

// :core —— 所有插件共用的设备 I/O 基座
class DeviceClient(device: Device) {
    suspend fun get(path: String): Json            // 读 /status /settings /level
    suspend fun post(path: String, body: Json)     // 发命令 /motor /tool /light
    fun frameStream(): Flow<Bitmap>                // MJPEG 帧流(仅摄像头)
    fun poll(path: String, intervalMs): Flow<Json> // 遥测轮询(声量、位置)
    val connection: StateFlow<ConnectionState>     // 统一在线状态
}
```

### 5.3 宿主挂载(注册表 + 自动路由)

```kotlin
val registry = CardRegistry()
    .register(CameraCardPlugin())      // :feature-camera
    // 以后: .register(MotionCardPlugin())  .register(LightCardPlugin()) ...

// 发现到 Device 后: registry[device.type]?.Tile(device)  →  点卡片进 Detail
```

### 5.4 端到端链路

1. 设备广播 `_esp32cam._tcp` → 发现层解析出 `Device(type="esp32cam")`
2. 注册表 `type → CameraCardPlugin`
3. `CameraCardPlugin.Detail` 用 `DeviceClient.frameStream()` 拉 MJPEG → Compose 渲染
4. 以后加温度传感器:`_tempsensor._tcp` → `type="tempsensor"` → 注册插件,`Device`/发现层/宿主零改动

### 5.5 设置页(两层叠加)

```
设置页(点卡片 ⚙ 进入)
├─ 通用设置(宿主统一提供):重命名 / 重新配网 / 删除设备
└─ 类型专属设置(插件 Settings 实现):摄像头 → 分辨率/帧率/旋转/画质(POST /settings)
```

### 5.6 离线状态(两来源喂同一状态)

1. **连接驱动(权威)**:`FrameSource` 连上=ONLINE,断=OFFLINE
2. **周期探测(快速,放 core)**:宿主每 ~5s 对已知设备发 HTTP HEAD `http://host/`,2s 超时判离线

### 5.7 录像 / 截图(均 App 端,固件不加端点)

- 截图:Detail 页按钮,把当前 `Bitmap` 存相册。
- 录像:Detail 页按钮,录制期间把 `frameStream()` 的帧经 `MediaCodec`(H.264)+ `MediaMuxer` 封装成 MP4。**无音频**(板子无麦克风)。

---

## 6. 里程碑

**M1(摄像头项目)** ✅ 全部完成
- 固件 `board-camera` v0.2.0:配网 + mDNS + UDP 广播兜底 + 三端点 + MJPEG 流
- App `:core` + `:feature-camera` + `:app`:配网流程(`WifiNetworkSpecifier` 跳热点)+ 发现(mDNS + UDP 兜底)+ 卡片容器 + 视频渲染 + 截图存相册 + 录像 MP4(MediaCodec,无音频)+ 重命名/删除设备(持久化)
- 验收:烧录 → App 配网 → 发现 → 看视频 ✅ 截图/录像 ✅

**M2**
- **App 改名 → khw_hub**(包名保留 `com.khwlab.hub`;显示名、TopBar、相册目录 `Pictures/khw_hub` / `Movies/khw_hub`、权限文案、文档标题全部更新)✅ 已完成(2026-09-06,构建通过)。
- **固件 0.2.6 修复**(M1 收尾,2026-09-06):
  - 长按 BOOT(GPIO0)4s 清凭据回配网 **不再被 /stream 饿死**(WebServer 单线程,/stream 死循环占住 loop → 改为独立 FreeRTOS 任务轮询,20ms)。
  - 新增 **`POST /unpair`** 端点:App 删除设备时调用 → 清 WiFi 凭据重启回配网态 → 设备真正断网不再被发现。
- 共享令牌鉴权(配网时生成,策略 A);`DeviceClient` 加 WebSocket 通道(实时连续控制);BLE 配网(优先,SoftAP 兜底);模式 LED 状态机;接入第一个新产品 **电磁锁控制系统**(Seeed XIAO ESP32-C3,3V 电磁锁)。

**M3+**
- 其余智能产品逐个接入 `lib/common` + `:feature-*`

---

## 7. 风险与阻塞

| 风险 | 说明 | 对策 |
|---|---|---|
| ~~黑屏~~ ✅ 已解决 | 板型/引脚选错 | **已确认:板子 = ESP32-S3-EYE 风格引脚**,即 `CAMERA_MODEL_ESP32S3_EYE`(XCLK15/SDA4/SCL5/D0-7=11,9,8,10,12,18,17,16/VSYNC6/HREF7/PCLK13)。GC2145 需 `PIXFORMAT_RGB565`(无硬件 JPEG),用 `frame2jpg()` 软编码 |
| Android `NsdManager` 兼容 | 小米/三星偶发 bug | UDP 广播兜底(协议不冲突,可并存) |
| SoftAP 配网体验 | 设备热点无外网,部分手机会想跳走 | `WifiNetworkSpecifier` 扛住,一次性可接受 |
| 摄像头可能倒装 | 画面 180° 反 | `set_vflip`/`set_hmirror` + App 侧 Matrix 旋转留开关 |
| 录像无音频 | 板子无麦克风 | M1 明确不做,要声音另加 I2S 麦克风(独立项目) |
| 局域网裸奔 | M1 无鉴权 | M2 加共享令牌;真安全需 TLS,重量级暂不做 |
| SoftAP 配网体验 | 设备热点无外网,部分手机会想跳走 | **M2 换 BLE 配网(首选)**,SoftAP 兜底保留 |
| 电磁锁电流 | 3V 锁峰值电流未知 | 固件按 GPIO 低边输出设计,电路上 MOSFET(AO3400)扩流,不赌直驱 |

---

## 8. 下一步

1. ✅ 本计划落盘
2. ⏭ 定位无名板 GC2145 的引脚/板型配置,解决黑屏
3. ⏭ 搭 M1 固件 `board-camera`
4. ⏭ 搭 App `:core` 骨架(Compose + MVVM + CardPlugin)
5. ⏭ 联调:配网 → 发现 → 看视频
