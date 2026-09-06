/*
 * ESP32-S3-CAM 摄像头固件 (M1/M2, khw_hub 产品一)
 * - 摄像头: GC2145,ESP32-S3-EYE 风格引脚
 * - 配网:   BLE 优先(KHW-CAM-xxxx)+ SoftAP 兜底(ESP32-CAM-Setup),两者并存(CONTRACT §7)
 * - 发现:   mDNS "_esp32cam._tcp" + UDP 3333
 * - 模式 LED: 外接单色 LED(GPIO21,编译期宏 KHW_MODE_LED_PIN),8 态(CONTRACT §6)
 * - API:    /status /config 免令牌; /settings /stream /action /unpair 令牌; /ws 8080 ?token=
 * - 复用 lib/common:令牌/动作总线/模式LED/BLE配网/WS/HTTP 底座/配网状态机
 *
 * 烧录设置: ESP32S3 Dev Module, 16MB flash, OPI PSRAM,
 *           USB CDC On Boot = Disabled, Upload Speed = 115200
 */
#define KHW_BOARD_TYPE "camera"
#define KHW_DEVICE_NAME_PREFIX "KHW-CAM"
#define KHW_SERVICE_TYPE "esp32cam"
#define KHW_MODE_LED_GPIO
#define KHW_MODE_LED_PIN 21
#define KHW_BOOT_PIN 0

#include "lib_common.h"
#include "esp_camera.h"
#include <ESPmDNS.h>
#include <WiFiUdp.h>
#include <lwip/sockets.h>
#include <errno.h>

// ============ Stream 发送辅助(handleStream 用) ============
#define STREAM_BUDGET_MS 3000   // 单次有界发送预算,详见 streamWriteAll

// ============ 已确认的摄像头引脚 (ESP32-S3-EYE 风格) ============
#define CAM_PIN_PWDN   -1
#define CAM_PIN_RESET  -1
#define CAM_PIN_XCLK   15
#define CAM_PIN_SIOD   4
#define CAM_PIN_SIOC   5
#define CAM_PIN_D7     16
#define CAM_PIN_D6     17
#define CAM_PIN_D5     18
#define CAM_PIN_D4     12
#define CAM_PIN_D3     10
#define CAM_PIN_D2     8
#define CAM_PIN_D1     9
#define CAM_PIN_D0     11
#define CAM_PIN_VSYNC  6
#define CAM_PIN_HREF   7
#define CAM_PIN_PCLK   13

#define AP_SSID       "ESP32-CAM-Setup"
#define FW_VERSION    "0.3.0"   // 0.3.0(M2):接入 lib/common —— BLE 配网 + 共享令牌 + 模式LED + /ws + /action;
                                //             /settings /stream 加令牌;/config 返回 token;配网改异步(不再阻塞 15s);
                                //             BOOT 4s 由 provision 状态机统一处理(清凭据+清token)。0.2.6:独立
                                //             factory reset 任务;/unpair 端点。0.2.5:rebuildCamera 每次从全局
                                //             设置同步 frame_size/quality 再 deinit+init。0.2.4:回退分支更新 config
                                //             导致所有分辨率按启动尺寸 init 的 bug。0.2.3:设置 POST 值截断修复
                                //             vflip/hmirror 保存。0.2.2:有界 streamWriteAll 断开假死 /stream。
                                //             0.2.1:robust JSON 配网。
#define DEVICE_TYPE   "esp32cam"

// UDP 发现兜底:App 发 "KHLAB_DISCOVER",设备回广播(CONTRACT §3)
#define UDP_PORT      3333
#define DISCOVER_MAGIC "KHLAB_DISCOVER"

// ============ 全局 ============
Preferences prefs;       // 摄像头设置(cam ns;wifi/sec/device 由 lib/common 管理)
WiFiUDP udp;
bool   udpActive = false;

// 周期性 UDP 广播的设备信息 JSON(内存复用,避免每次拼接/分配)
String udpAdvertise = "";

String deviceId = "";   // esp32cam-xxxxxx
bool   apMode   = false;

// 摄像头设置
int  g_framesize = FRAMESIZE_XGA;   // framesize_t 索引(默认 1024x768,原 VGA 640x480 用户嫌低)
int  g_quality   = 12;              // 0-63,越小越清晰
bool g_vflip     = false;
bool g_hmirror   = false;

// ============ 摄像头初始化 ============

camera_config_t config;   // 全局:initCamera 填充一次,rebuildCamera 复用(含切换分辨率)

// GC2145 翻转/镜像寄存器:0x17 = P0_ANALOG_MODE1。
// 事实来源(上游 espressif/esp32-camera sensors/gc2145.c + gc2145_regs.h):
//   set_hmirror → set_reg_bits(slv, 0x17, 0, 0x01, enable)   // bit0 = 水平镜像
//   set_vflip   → set_reg_bits(slv, 0x17, 1, 0x01, enable)   // bit1 = 垂直翻转
// 初始化表默认把 0x17 写成 0x14(见 gc2145_settings.h),所以每次 init 之后
// 必须显式重写 0x17 的 bit0/bit1,否则翻转/镜像设置会被默认值覆盖。
// 注意:s->set_vflip/set_hmirror 在部分驱动(esp_cam_sensor NEW)里是坏的
// (VFLIP 误写 reg 0x10 = 窗口宽度低字节,既不改翻转还破坏宽度→花屏),故这里
// 不用函数指针,直接走公开的 set_reg 按位读写 0x17,行为与上游源码一致。
#define GC2145_ANALOG_MODE1 0x17
#define GC2145_BIT_HMIRROR  0x01
#define GC2145_BIT_VFLIP    0x02

// 切换分辨率后必须把摄像头整个重建(deinit+init)才能清掉 DMA 行缓冲/描述符残留;
// 单靠 set_framesize + 丢帧无法消除拼接残影(320×240 帧率高、残影停留短,看着"正常",
// 低帧率分辨率才会拼出明显的多画面伪影)。重建后按当前设置重放翻转/镜像与画质。
bool rebuildCamera() {
  // 每次重建都先从当前全局设置同步帧尺寸/画质:initCamera 只填一次 config 的
  // 其余字段,但 frame_size/quality 会随设置页改动 —— 若不在这里同步,
  // esp_camera_init 会一直按启动时的尺寸 init,分辨率/画质切换看起来不生效
  // (0.2.5 修复:0.2.4 只在回退分支更新 config,正常路径切分辨率后相机仍按旧尺寸重建)。
  config.frame_size   = (framesize_t)g_framesize;
  config.jpeg_quality = g_quality;
  esp_err_t err = esp_camera_deinit();
  if (err != ESP_OK) {
    Serial.printf("Camera deinit failed 0x%x\n", (unsigned)err);
    // deinit 失败不代表相机不可用,继续尝试 init
  }
  err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("Camera init failed 0x%x\n", (unsigned)err);
    return false;
  }
  sensor_t* s = esp_camera_sensor_get();
  if (s) {
    // set_reg(reg, mask, value):mask 为 0 表示直接写整字节,这里都带掩码按位改
    s->set_reg(s, GC2145_ANALOG_MODE1, GC2145_BIT_HMIRROR, g_hmirror ? GC2145_BIT_HMIRROR : 0);
    s->set_reg(s, GC2145_ANALOG_MODE1, GC2145_BIT_VFLIP, g_vflip ? GC2145_BIT_VFLIP : 0);
    s->set_quality(s, g_quality);
  }
  return true;
}

bool initCamera() {
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer   = LEDC_TIMER_0;
  config.pin_d0  = CAM_PIN_D0;
  config.pin_d1  = CAM_PIN_D1;
  config.pin_d2  = CAM_PIN_D2;
  config.pin_d3  = CAM_PIN_D3;
  config.pin_d4  = CAM_PIN_D4;
  config.pin_d5  = CAM_PIN_D5;
  config.pin_d6  = CAM_PIN_D6;
  config.pin_d7  = CAM_PIN_D7;
  config.pin_xclk     = CAM_PIN_XCLK;
  config.pin_pclk     = CAM_PIN_PCLK;
  config.pin_vsync    = CAM_PIN_VSYNC;
  config.pin_href     = CAM_PIN_HREF;
  config.pin_sccb_sda = CAM_PIN_SIOD;
  config.pin_sccb_scl = CAM_PIN_SIOC;
  config.pin_pwdn     = CAM_PIN_PWDN;
  config.pin_reset    = CAM_PIN_RESET;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_RGB565;   // GC2145 无硬件 JPEG
  config.frame_size   = (framesize_t)g_framesize;
  config.jpeg_quality = g_quality;
  config.fb_count     = 2;
  config.fb_location  = CAMERA_FB_IN_PSRAM;
  config.grab_mode    = CAMERA_GRAB_WHEN_EMPTY;
  return rebuildCamera();
}

// ============ 设置持久化 (NVS, "cam" ns) ============
void loadSettings() {
  prefs.begin("cam", true);
  g_framesize = prefs.getInt("framesize", FRAMESIZE_XGA);
  g_quality   = prefs.getInt("quality", 12);
  g_vflip     = prefs.getBool("vflip", false);
  g_hmirror   = prefs.getBool("hmirror", false);
  prefs.end();
}

void saveSettings() {
  prefs.begin("cam", false);
  prefs.putInt("framesize", g_framesize);
  prefs.putInt("quality", g_quality);
  prefs.putBool("vflip", g_vflip);
  prefs.putBool("hmirror", g_hmirror);
  prefs.end();
}

void applySettings() {
  sensor_t* s = esp_camera_sensor_get();
  if (!s) return;

  // 分辨率/画质变更 → 重建相机管线(见 rebuildCamera 注释,修 GC2145 花屏/多画面伪影)。
  // 重建失败回退旧分辨率:先记下旧值,新值生效后若 init 失败,恢复旧值再重建一次。
  int  old_framesize = g_framesize;
  int  old_quality   = g_quality;
  if (!rebuildCamera()) {
    Serial.printf("Camera rebuild failed, falling back to old settings (%d -> %d)\n",
                  old_framesize, g_framesize);
    g_framesize = old_framesize;
    g_quality   = old_quality;
    config.frame_size   = (framesize_t)g_framesize;
    config.jpeg_quality = g_quality;
    rebuildCamera();
  }
  s = esp_camera_sensor_get();
  if (s) {
    // 重建(或回退重建)后传感器对象是新的,重放翻转/镜像与画质(见 rebuildCamera 注释)
    s->set_reg(s, GC2145_ANALOG_MODE1, GC2145_BIT_HMIRROR, g_hmirror ? GC2145_BIT_HMIRROR : 0);
    s->set_reg(s, GC2145_ANALOG_MODE1, GC2145_BIT_VFLIP, g_vflip ? GC2145_BIT_VFLIP : 0);
    s->set_quality(s, g_quality);
  }
}

// ============ 工具 ============
String urlDecode(const String& s) {
  String out = "";
  for (int i = 0; i < (int)s.length(); i++) {
    char c = s[i];
    if (c == '+') out += ' ';
    else if (c == '%' && i + 2 < (int)s.length()) {
      out += (char)strtol(s.substring(i + 1, i + 3).c_str(), NULL, 16);
      i += 2;
    } else out += c;
  }
  return out;
}

// 从 JSON body 按 key 提取字符串值,支持 \"、\\、\n 等转义;找不到返回 false
bool extractJsonString(const String& body, const char* key, String& out) {
  String k = "\"" + String(key) + "\"";
  int p = body.indexOf(k);
  if (p < 0) return false;
  p += k.length();   // 跳到 key 右引号之后
  while (p < (int)body.length() && body[p] != ':') p++;   // 跳到冒号
  p++;   // 越过冒号指向值(不能省:否则停在 ':' 上,下方开引号检查恒失败 → 400)
  while (p < (int)body.length() &&
         (body[p] == ' ' || body[p] == '\t' || body[p] == '\r' || body[p] == '\n')) p++;  // 跳过空白
  if (p >= (int)body.length() || body[p] != '"') return false;
  p++;
  out = "";
  while (p < (int)body.length()) {
    char c = body[p];
    if (c == '\\' && p + 1 < (int)body.length()) {
      char n = body[p + 1];
      if (n == '"' || n == '\\' || n == '/') { out += n; p += 2; continue; }
      if (n == 'n') { out += '\n'; p += 2; continue; }
      if (n == 'r') { out += '\r'; p += 2; continue; }
      if (n == 't') { out += '\t'; p += 2; continue; }
      out += c; p++;
    } else if (c == '"') {
      p++;
      break;
    } else {
      out += c; p++;
    }
  }
  return true;
}

// 解析 /config body,兼容 JSON(App)与 form-urlencoded(浏览器)
bool parseConfig(const String& body, String& ssid, String& pass) {
  ssid = ""; pass = "";
  if (body.indexOf('{') >= 0) {
    extractJsonString(body, "ssid", ssid);
    extractJsonString(body, "password", pass);
  } else {
    int start = 0;
    while (start < (int)body.length()) {
      int eq  = body.indexOf('=', start);
      int amp = body.indexOf('&', start);
      if (amp < 0) amp = body.length();
      if (eq < 0 || eq > amp) { start = amp + 1; continue; }
      String key = body.substring(start, eq);
      String val = urlDecode(body.substring(eq + 1, amp));
      if (key == "ssid") ssid = val;
      else if (key == "password") pass = val;
      start = amp + 1;
    }
  }
  return ssid.length() > 0;
}

// ============ HTTP 处理器(全部走 lib 共享服务器实例,端口 80) ============

void handleSettingsGet() {
  WebServer& srv = khwHttpServer();
  if (!khwHttpAuthOK()) { khwHttpSend401(); return; }
  String json = "{";
  json += "\"resolution\":" + String(g_framesize) + ",";
  json += "\"quality\":" + String(g_quality) + ",";
  json += "\"vflip\":" + String(g_vflip ? "true" : "false") + ",";
  json += "\"hmirror\":" + String(g_hmirror ? "true" : "false");
  json += "}";
  srv.send(200, "application/json", json);
}

void handleSettingsPost() {
  WebServer& srv = khwHttpServer();
  if (!khwHttpAuthOK()) { khwHttpSend401(); return; }
  String body = srv.arg("plain");
  // 解析 resolution / quality / vflip / hmirror (App 端 POST 的 JSON body;浏览器不发设置 POST)。
  // 值截断到逗号或右花括号再取,避免取到后续字段("true,..." 整串比较恒失败 → 翻转/镜像存不上)。
  auto parseValue = [&](const char* key, String& out) -> bool {
    int p = body.indexOf(key);
    if (p < 0) return false;
    int c = body.indexOf(':', p);
    if (c < 0) return false;
    p = c + 1;
    while (p < (int)body.length() && (body[p] == ' ' || body[p] == '\t')) p++;
    int q = p;
    while (q < (int)body.length() && body[q] != ',' && body[q] != '}' && body[q] != '\r' && body[q] != '\n') q++;
    out = body.substring(p, q);
    out.trim();
    return out.length() > 0;
  };
  auto getInt = [&](const char* key, int def) -> int {
    String v;
    if (!parseValue(key, v)) return def;
    return v.toInt();
  };
  auto getBool = [&](const char* key, bool def) -> bool {
    String v;
    if (!parseValue(key, v)) return def;
    if (v == "true" || v == "1") return true;
    if (v == "false" || v == "0") return false;
    return def;
  };

  g_framesize = getInt("resolution", g_framesize);
  g_quality   = getInt("quality", g_quality);
  g_vflip     = getBool("vflip", g_vflip);
  g_hmirror   = getBool("hmirror", g_hmirror);
  saveSettings();
  // 不用在这里断流:WebServer 单线程,能走到 POST handler 说明没有在服务的流连接
  // (若有 /stream 在跑,handleClient 根本不会回来处理 POST)。停掉 client() 反而
  // 会掐断本连接——handleSettingsGet 的响应就发不出去了。
  applySettings();
  handleSettingsGet();
}

// 配网 /config —— 免令牌(SoftAP/BLE 配网态);覆盖 lib 的 JSON-only 版:
// 兼容浏览器 form + JSON,响应带 deviceId 与共享 token(CONTRACT §1 §2)。
void handleConfig() {
  WebServer& srv = khwHttpServer();
  String ssid, pass;
  String body = srv.arg("plain");
  if (body.length() > 0) {
    // JSON(App)或 form-urlencoded(body 透传)
    parseConfig(body, ssid, pass);
  } else {
    // 浏览器表单 form-urlencoded(WebServer 已解析成 arg)
    ssid = srv.arg("ssid");
    pass = srv.arg("password");
  }
  if (ssid.length() == 0 || ssid.length() > 32) {
    srv.send(400, "application/json", "{\"ok\":false,\"error\":\"bad-ssid\"}");
    return;
  }
  khwConfigSet("wifi", "ssid", ssid);
  khwConfigSet("wifi", "pass", pass);
  khwTokenEnsure();  // 生成/保证共享令牌(配网响应带回 App)
  String resp = String("{\"ok\":true,\"deviceId\":\"") + deviceId +
                "\",\"token\":\"" + khwTokenGet() + "\"}";
  srv.send(200, "application/json", resp);
  delay(300);
  ESP.restart();
}

// 动作总线(CONTRACT §4):摄像头板注册 status(等价 /status 摘要)与 reboot(可选)
static String handleAction(const String& action, const String& /*params*/) {
  if (action == "status") {
    String body = String("{\"ok\":true,\"deviceId\":\"") + deviceId +
                  "\",\"mode\":\"" + (apMode ? "ap" : "online") +
                  "\",\"fwVersion\":\"" FW_VERSION "\"}";
    khwWsSend(String("{\"type\":\"status\",\"data\":") + body + "}");
    return body;
  }
  if (action == "reboot") {
    khwWsSend("{\"type\":\"status\",\"data\":{\"ok\":true,\"state\":\"reboot\"}}");
    delay(500);
    ESP.restart();
  }
  return "{\"ok\":false,\"error\":\"bad-action\"}";
}

void handleStream() {
  WebServer& srv = khwHttpServer();
  if (!khwHttpAuthOK()) { khwHttpSend401(); return; }
  WiFiClient client = srv.client();
  client.setNoDelay(true);   // 禁 Nagle:帧小、低延迟,数据尽快推给对端
  // 整条流(含 HTTP 头)都走 streamWriteAll 的有界发送:
  // 假死连接(对端消失、socket 半开)在预算时间内无进展即返回 false → 断开,
  // handleStream 立即返回,WebServer 马上能接新 /stream → 退出重进可秒连。
  // 不能走 NetworkClient::write:它内部 select 重试上限 10×1s,最坏卡 10s,
  // 且 send 用 MSG_DONTWAIT + select,SO_SNDTIMEO 拦不住 —— 必须自己控时。
  String boundary = "frame";
  String headers = "HTTP/1.1 200 OK\r\n"
                   "Content-Type: multipart/x-mixed-replace; boundary=" + boundary + "\r\n"
                   "\r\n";
  if (!streamWriteAll(client, (const uint8_t*)headers.c_str(), headers.length(), STREAM_BUDGET_MS)) {
    client.stop();
    return;
  }
  uint32_t srvTick = 0;   // 流内分片:lib/common 维护(BOOT 检测/WS 心跳/LED 状态机)
  while (client.connected()) {
    uint32_t now = millis();
    if (now - srvTick >= 200) {
      // CONTRACT §6:摄像头板不新建任务,并入 loop();流服务期间 loop 被占,
      // 这里分片推进,保证长按 BOOT 4s、WS 心跳、LED 状态机持续生效
      khwProvisionLoop();
      khwWsLoop();
      KhwModeLed::tick();
      srvTick = now;
    }
    camera_fb_t* fb = esp_camera_fb_get();
    if (!fb) break;   // 拿不到帧就断开(下一轮 /stream 会重新进;总比卡在这里好)
    if (fb->format == PIXFORMAT_JPEG) {
      String head = "--" + boundary + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + String(fb->len) + "\r\n\r\n";
      if (!streamWriteAll(client, (const uint8_t*)head.c_str(), head.length(), STREAM_BUDGET_MS)) {
        esp_camera_fb_return(fb);
        break;
      }
      if (!streamWriteAll(client, fb->buf, fb->len, STREAM_BUDGET_MS)) {
        esp_camera_fb_return(fb);
        break;
      }
      esp_camera_fb_return(fb);
    } else {
      uint8_t* jpg = NULL;
      size_t   jpgLen = 0;
      bool ok = frame2jpg(fb, g_quality, &jpg, &jpgLen);
      esp_camera_fb_return(fb);
      if (!ok) { delay(50); continue; }   // 软编码失败:等一小会儿再取下一帧
      String head = "--" + boundary + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + String(jpgLen) + "\r\n\r\n";
      if (!streamWriteAll(client, (const uint8_t*)head.c_str(), head.length(), STREAM_BUDGET_MS)) {
        free(jpg);
        break;
      }
      if (!streamWriteAll(client, jpg, jpgLen, STREAM_BUDGET_MS)) {
        free(jpg);
        break;
      }
      free(jpg);
    }
  }
  client.stop();
}

// 配网网页(仅 AP 模式)
const char CONFIG_HTML[] = R"rawliteral(
<!DOCTYPE html><html><head><meta charset="utf-8"><title>ESP32-CAM Setup</title></head>
<body><h2>ESP32-CAM 配网</h2>
<form action="/config" method="post">
  SSID: <input name="ssid"><br><br>
  密码: <input name="password" type="password"><br><br>
  <input type="submit" value="连接">
</form></body></html>
)rawliteral";

void handleRoot() {
  WebServer& srv = khwHttpServer();
  if (apMode) {
    srv.send(200, "text/html", CONFIG_HTML);
  } else {
    srv.send(200, "text/plain", "ESP32-CAM running. Stream: /stream");
  }
}

// ============ Stream 发送辅助(handleStream 用) ============
/**
 * 有界发送(整块在预算内必须推给对端,否则判死连接):
 * - 不用 NetworkClient::write:它内部 select 固定等 1s、最多重试 10 次,
 *   半开假死连接最坏阻塞 10s;这里直接用 lwip select(100ms)自己控时。
 * - select 100ms + 每步查预算:任何情况(对端 FIN/RST、断网半开)总时长
 *   都 ≤ 预算(调用方传 STREAM_BUDGET_MS=3s);有进展就顺延预算 → 慢但活的连接不误杀。
 * - send 失败且非 EAGAIN(对端 RST/EPIPE 等)立即判死返回 false。
 * 返回 true = 整块写完;false = 连接不可写,调用方应断开。
 */
bool streamWriteAll(NetworkClient& c, const uint8_t* buf, size_t len, uint32_t budgetMs) {
  int fd = c.fd();
  if (fd < 0) return false;
  size_t written = 0;
  unsigned long deadline = millis() + budgetMs;
  while (written < len) {
    fd_set w;
    FD_ZERO(&w);
    FD_SET(fd, &w);
    struct timeval tv = { 0, 100000 };   // 100ms:不抢 WebServer,也不怕卡
    int s = select(fd + 1, NULL, &w, NULL, &tv);
    if (s < 0) return false;             // 真错误,直接判死
    if (s == 0) {
      if (millis() >= deadline) return false;   // 无进展且超预算 → 死连接
      continue;
    }
    int n = send(fd, buf + written, len - written, MSG_DONTWAIT);
    if (n > 0) {
      written += n;
      deadline = millis() + budgetMs;    // 有进展就顺延(慢但活的不误杀)
    } else if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
      return false;                       // 对端已断开(RST/EPIPE 等)
    }
    // n == 0 或 EAGAIN:继续等下一轮 select
  }
  return true;
}

// ============ SoftAP 兜底 ============
void startAp() {
  apMode = true;
  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID);
  Serial.printf("AP mode: %s (192.168.4.1)\n", AP_SSID);
}

// ============ UDP 广播发现(STA 入网后才启动) ============
void startUdp() {
  if (!udp.begin(UDP_PORT)) {
    Serial.println("UDP begin failed");
    return;
  }
  udpActive = true;
  udpAdvertise = "{\"id\":\"" + deviceId + "\",\"type\":\"" DEVICE_TYPE
                 "\",\"fwVersion\":\"" FW_VERSION
                 "\",\"host\":\"" + WiFi.localIP().toString() +
                 "\",\"port\":" + String(80) + "}";
  Serial.println("UDP discover listening on port " + String(UDP_PORT));
}

void handleUdp() {
  if (!udpActive) return;
  int n = udp.parsePacket();
  if (n <= 0) return;
  // 只认我们自己的 magic(长度不符直接丢弃)
  if (n < (int)strlen(DISCOVER_MAGIC)) return;
  char buf[32] = {0};
  udp.read(buf, sizeof(buf) - 1);
  if (strncmp(buf, DISCOVER_MAGIC, strlen(DISCOVER_MAGIC)) != 0) return;
  // 收到查询:单播回给查询方(及时,不依赖广播可达性)
  IPAddress peer = udp.remoteIP();
  if (peer != IPAddress(0, 0, 0, 0)) {
    udp.beginPacket(peer, udp.remotePort());
    udp.print(udpAdvertise);
    udp.endPacket();
  }
}

void udpAdvertiseLoop() {
  static unsigned long last = 0;
  if (udpActive && millis() - last > 2000) {
    last = millis();
    udp.beginPacket(IPAddress(255, 255, 255, 255), UDP_PORT);
    udp.print(udpAdvertise);
    udp.endPacket();
  }
}

// ============ 入口 ============
void setup() {
  Serial.begin(115200);
  delay(1000);

  // 设备 ID(MAC 后 6 位)并落盘 NVS "device"/"id"(lib ws hello 与 /status 依赖)
  uint64_t mac = ESP.getEfuseMac();
  char id[16];
  snprintf(id, sizeof(id), "esp32cam-%06x", (unsigned)(mac & 0xFFFFFF));
  deviceId = String(id);
  khwConfigSet("device", "id", deviceId);
  khwTokenEnsure();            // M1 存量设备升级:补齐共享令牌(CONTRACT §2)

  pinMode(KHW_BOOT_PIN, INPUT_PULLUP);   // BOOT 键:长按 4s 清凭据+清token 回配网态(provision 状态机检测)
  loadSettings();                        // 摄像头设置(cam ns)

  KhwModeLed::begin();                   // BOOT:白快闪
  khwHttpSetFwVersion(FW_VERSION);

  // 板级专属路由必须先于 khwHttpSetup() 注册:WebServer 按注册顺序匹配、先注册者胜,
  // /config 若在其后才注册会被 lib 的 JSON-only 版遮蔽 → 浏览器 form 配网 400 bad-ssid。
  // (端口 80 复用 lib 服务器实例,见 khwHttpServer 访问器)
  WebServer& srv = khwHttpServer();
  srv.on("/", handleRoot);
  srv.on("/config", HTTP_POST, handleConfig);   // 覆盖 lib JSON-only 版:兼容浏览器 form + 返回 token
  srv.on("/settings", HTTP_GET, handleSettingsGet);
  srv.on("/settings", HTTP_POST, handleSettingsPost);
  srv.on("/stream", handleStream);

  khwHttpSetup();                        // lib 底座:/status /config /action /unpair
  khwWsSetup();
  khwWsSetFwVersion(FW_VERSION);
  khwActionRegister(handleAction);       // 摄像头板动作总线:status / reboot

  if (!initCamera()) {
    Serial.println("Camera init failed, reboot in 3s...");
    delay(3000);
    ESP.restart();
  }

  // 配网:BLE 优先 + SoftAP 兜底,两者并存(CONTRACT §7)。
  // 有凭据 → 异步 STA_CONNECTING(不再同步阻塞 15s,LED 黄快闪);
  // 无凭据 → BLE 广播(KHW-CAM-xxxx)+ SoftAP(ESP32-CAM-Setup)同时开。
  String ssid;
  bool hasCreds = khwConfigGet("wifi", "ssid", ssid) && ssid.length() > 0;
  khwProvisionSetup();
  if (!hasCreds) {
    khwBleSetup();
    startAp();
    khwProvisionSet(ProvisionState::SOFTAP);
  }
  Serial.println("HTTP server ready");
}

void loop() {
  khwProvisionLoop();      // BOOT 4s 检测 + STA_CONNECTING 超时兜底
  khwBleLoop();            // BLE 配网结果推进(无凭据态才活跃)
  khwHttpLoop();
  khwWsLoop();
  KhwModeLed::tick();

  // 入网成功后启动 mDNS + UDP 发现(配网态不起;断线自动停,重连再启)
  if (WiFi.status() == WL_CONNECTED && !udpActive) {
    if (MDNS.begin(deviceId.c_str())) {
      MDNS.addService("_esp32cam", "_tcp", 80);
    }
    startUdp();
  } else if (WiFi.status() != WL_CONNECTED && udpActive) {
    udpActive = false;   // 断线停 UDP 发现,重连后再启
  }
  handleUdp();
  udpAdvertiseLoop();
  delay(2);
}
