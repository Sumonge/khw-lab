# M2 共享契约(固件 ⇄ App 实现级契约)

> 本文档是 M2 唯一权威实现依据(超集)。固件 `lib/common`、`board-lock`、`board-camera` 与 App `:core`/`:feature-lock`/`:app` 都以本文件为准;`../PLAN.md` 4.3–4.8 为设计摘要。
> 约定:`1.0` = 请求/响应逐字段、JSON 逐键、Header 逐名核准。三遍验证(两板语法校验 + App 构建 + 契约一致性)按本文件逐项核对。

## 1. 设备标识与端点总表

| 端点 | 方法 | 鉴权 | 可用模式 | 说明 |
|---|---|---|---|---|
| `/status` | GET | **免令牌** | 运行态 | 身份/健康探测 `{id,name,type,fwVersion,mode,token?}`(`token` 仅 BLE/SoftAP 配网响应携带) |
| `/config` | POST | **免令牌** | 配网态(SoftAP 192.168.4.1) | `{ssid,password}` → 存 NVS → 重启;响应 `{ok,deviceId,token}` |
| `/stream` | GET | 令牌 `X-Khw-Token` | 运行态(仅摄像头) | MJPEG multipart(保持 M1) |
| `/settings` | GET/POST | 令牌 | 运行态 | 读/写板级配置 |
| `/action` | POST | 令牌 | 运行态 | 动作总线(见 §4) |
| `/unpair` | POST | 令牌 | 运行态 | 清 WiFi 凭据 + 清 token → 重启回配网态 |
| `/ws` | GET(升级) | 令牌 `?token=` | 运行态 | WebSocket 端口 **8080**(§5) |

## 2. 共享令牌

- **格式**:16 位十六进制小写(`[0-9a-f]{16}`),由 `esp_random()` 分 2 次 ×8 字节派生,或 `ESP.getEfuseMac()` 派生 + 随机盐,写入 NVS 键 `sec/token`。
- **生成时机**:配网成功落盘时;M1 存量设备首次入网 `ensure()` 补齐。
- **下发时机**:
  - BLE 路径:`STATUS` 特征 Notify `{"status":"ok","token":"...","host":"..."}`。
  - SoftAP 路径:`POST /config` 响应 `{"ok":true,"deviceId":"...","token":"..."}`。
- **携带方式**:
  - HTTP:`X-Khw-Token: <token>` 请求头。
  - WebSocket:握手 URI `ws://host:8080/ws?token=<token>`。
- **校验失败**:`401 {"ok":false,"error":"unauthorized"}`;WS 鉴权失败即断开。
- **例外(免令牌)**:`GET /status`、`POST /config` —— `/status` 是发现/探测端点,App 在拿到 token 前需要它;/config 是配网态端点,尚无 token。
- App 侧:`PreferencesDeviceStore` 增加每设备 `token` 持久化;`DeviceClient` 构造时带上 token 注入 `X-Khw-Token`。

## 3. 发现

- mDNS:每板广播自有服务类型 —— 摄像头 `_esp32cam._tcp`(M1 保持);锁板 `_esp32lock._tcp`。
- UDP 兜底:端口 3333,magic `KHLAB_DISCOVER`,响应 JSON 含 `type` 字段(`esp32cam`/`esp32lock`)——App 已按 `type` 解析,天然兼容。
- App `HomeViewModel`:锁板设备 `type="esp32lock"`,按注册表路由到 `:feature-lock` 卡片。

## 4. 动作总线 POST /action

```
请求  {"action":"<name>","params":{...}}      // params 板级自定义,可缺省为 {}
响应  {"ok":true,"data":{...}}                // 成功
      {"ok":false,"error":"<code>"}           // 失败,code ∈ wifi|unauthorized|bad-action|busy|hw
```

**锁板**(`:feature-lock` 按此实现):

| action | params | data |
|---|---|---|
| `lock` | 无 | `{"state":"locked"}` |
| `unlock` | 无 | `{"state":"unlocked"}` |
| `toggle` | 无 | `{"state":"locked"\|"unlocked"}` |
| `status` | 无 | `{"state":"locked"\|"unlocked","since":<epoch_ms>}` |

- 动作执行后:模式 LED 触发「动作橙短闪」;状态变化推 WS(见 §5)。
- 动作互斥:动作执行期间返回 `busy`;锁动作 ≤500ms(继电器/电磁锁吸合时间),超过判 `hw`。

**摄像头板**:注册 `status`(= `/status` 等价)与 `reboot`(可选)。其余产品动作见各自设计。

## 5. WebSocket(/ws,端口 8080)

- 监听端口 **8080**,路径 `/ws`,鉴权 `?token=<token>`。标准 RFC6455 握手(SHA1 + base64 GUID)。单客户端;新连接顶替旧连接。
- 文本帧(FIN=1),JSON。设备→App 推送;App→设备命令可选(HTTP 够用时不用)。

**设备→App push**:

```
{"type":"status","data":{"state":"locked","since":1234567890}}   // 状态变化即推
{"type":"hello","data":{"deviceId":"esp32lock-xxxxxx","fwVersion":"0.1.0","mode":"online"}}  // 握手后首帧
```

**App→设备 cmd**(可选):

```
{"type":"action","data":{"action":"lock","params":{}}}
```

- 心跳:设备每 30s 回 ping(`{"type":"ping"}`),客户端回 pong 或忽略;客户端可发 `{"type":"ping"}`。
- 断开重连:App 侧 `WebSocketClient` 掉线后 2s 重连(MJPEG frameStream 同款节奏)。

## 6. 模式 LED 状态机(两块板统一)

见 PLAN §4.4 八态表。实现层差异:

- **WS2812 板**(XIAO C3,GPIO7):RMT 位banged 驱动(见 `lib/common/ModeLed.h`,`rmtInit(7, RMT_TX_MODE, RMT_MEM_NUM_BLOCKS_1, 10000000)` + `rmt_data_t`,100ns tick:bit0=4+8、bit1=8+4,GRB 顺序),**无第三方库**。
- **GPIO 板**(S3-CAM,外接单色 LED,引脚可配置,默认空闲 GPIO,编译期宏覆盖):digitalWrite 高/低电平 + 周期翻转,支持呼吸(非阻塞 PWM 或方波渐变)。
- 所有效果**非阻塞**(`millis()` 时间片状态机),LED 刷新并入 `loop()` 或独立轻量任务;S3-CAM 摄像头板并入 `loop()`(不新建任务,避免与 /stream 争 CPU)。

| 模式 | WS2812 色 | GPIO 电平 | 效果 |
|---|---|---|---|
| BOOT 上电自检 | 白 | 亮 | 快闪 3 次(300ms on/off) |
| PROVISION_READY 配网就绪 | 蓝 | 亮 | 呼吸慢闪(1.5s 周期) |
| PROVISIONING 配网中 | 蓝 | 亮 | 常亮 |
| CONNECTING 连接 WiFi | 黄 | 亮 | 快闪(200ms 周期) |
| ONLINE 在线 | 绿 | 亮 | 常亮 |
| ACTION 动作 | 橙 | 亮 | 短闪 1 次(100ms) |
| ERROR 异常 | 红 | 亮 | 慢闪(1s 周期) |
| RESET 重置中 | 红 | 亮 | 快闪(200ms 周期) |

## 7. BLE 配网契约

```
服务 UUID(每板一个,广播 + App 扫描过滤):
  摄像头板 (KHW_CAM)  :  6b687763-0000-1000-8000-00805f9b34fb   ("khw"+'c')
  锁板     (KHW_LOCK) :  6b68776c-0000-1000-8000-00805f9b34fb   ("khw"+'l')

特征 UUID(两板共用同一组,服务 UUID 区分产品):
  SSID   (Write)       6b6877d1-0000-1000-8000-00805f9b34fb
  PASS   (Write)       6b6877d2-0000-1000-8000-00805f9b34fb
  STATUS (Read/Notify) 6b6877d3-0000-1000-8000-00805f9b34fb
```

- **payload**:SSID/PASS 均为 UTF-8 文本;SSID 最大 32B、PASS 最大 64B;接收端按 `\0` 或长度截断。
- **流程**:App 扫描(过滤服务 UUID)→ GATT 连接 → 写 SSID → 写 PASS → 设备存 NVS 并连 WiFi → STATUS Notify:
  - 连接中:`{"status":"connecting"}`
  - 成功:`{"status":"ok","token":"<16hex>","host":"192.168.x.x"}` → 设备 1s 后重启入网
  - 失败:`{"status":"fail","error":"wifi"}`(SSID/密码错或未找到网络)→ 设备回 PROVISION_READY 重新广播
- App 收到 `ok` → 保存 `{deviceId, token, host}` → 切回家庭 WiFi → 正常发现。
- **兜底**:BLE 连不上 → 长按 BOOT 4s 清凭据重启回 SoftAP(M1 逻辑);SoftAP 配网(旧路径)保留,且返回 token(见 §2)。
- BLE 广播:`advertiseServiceData` 不强制;App 用服务 UUID 过滤即可。设备名形如 `KHW-CAM-xxxx` / `KHW-LOCK-xxxx`(便于人工识别)。

## 8. NVS 键表

| 命名空间 | 键 | 内容 | 写于 |
|---|---|---|---|
| `wifi` | `ssid` / `pass` | 家庭 WiFi 凭据 | 配网 |
| `sec` | `token` | 共享令牌 | 配网成功 |
| `device` | `id` | 设备 ID(缺省按 MAC 派生) | 首启 |
| `cam` | `framesize/quality/vflip/hmirror` | 摄像头设置(M1) | 设置 |
| `lock` | `state` | 锁最后状态 | 每次动作 |

## 9. App 契约(:core 扩展)

```kotlin
// DeviceClient 扩展(M2)
class DeviceClient(device: Device, token: String?) {
  suspend fun get(path: String): Json                     // 自动带 X-Khw-Token
  suspend fun post(path: String, body: Json): Json        // 同上
  suspend fun action(name: String, params: JsonObject = jsonObject{}) : Json  // POST /action
  suspend fun ws(): WebSocketChannel?                    // 连 /ws?token= ,StateFlow<String> 收 push
  fun frameStream(): Flow<Bitmap>                        // 仅摄像头(M1)
}
```

- `PreferencesDeviceStore`:`Device(id,type,host,port,name)` 持久化 + **新增 token**。
- 配网两种路径统一产出 `ProvisionResult(deviceId, token, host)`,由 ProvisionViewModel 持久化。
- BLE 配网:Android 12+ 蓝牙权限(扫描/连接)在 33+ 归 `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`;12–32 用 `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`(运行时)或旧 `BLUETOOTH`/`BLUETOOTH_ADMIN`(manifest)。扫描过滤 `6b687763-...`(camera)/`6b68776c-...`(lock)按产品选择。
- 锁卡片 `:feature-lock`:`Tile` 显示锁状态图标;`Detail` 大锁按钮(lock/unlock/toggle)+ 状态推送(WS);`Settings` 重命名/删除(宿主通用)+ 锁专属设置(默认动作、开锁时常)。
