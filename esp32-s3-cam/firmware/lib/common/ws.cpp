// lib/common:WebSocket 服务器(CONTRACT §5)
// 端口 8080,路径 /ws,鉴权 ?token=;标准 RFC6455 握手(SHA1 + base64 GUID)。
// 单客户端;新连接顶替旧连接。设备→App push: hello/status/ping;App→设备 cmd(可选)。
// 非阻塞: khwWsLoop() 并入 loop(),每轮处理一次。
#include "lib_common.h"
#include <WiFi.h>
#include <WebServer.h>
#include "mbedtls/sha1.h"
#include <base64.h>

#ifndef KHW_WS_PORT
#define KHW_WS_PORT 8080
#endif

#ifndef KHW_WS_PING_INTERVAL_MS
#define KHW_WS_PING_INTERVAL_MS 30000
#endif

#ifndef KHW_WS_HELLO_INTERVAL_MS
#define KHW_WS_HELLO_INTERVAL_MS 5000   // 入网/发现前 hello 兜底重发
#endif

static WiFiServer s_wsServer(KHW_WS_PORT);
static WiFiClient s_wsClient;
static bool      s_wsOpen = false;
static uint32_t  s_wsLastPing = 0;
static uint32_t  s_wsLastHello = 0;
static bool      s_wsHelloSent = false;

// SHA1 → base64 计算 (RFC6455 Sec-WebSocket-Accept)
static String wsAccept(const String& key) {
  String mac = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  uint8_t hash[20];
  mbedtls_sha1((const unsigned char*)mac.c_str(), mac.length(), hash);
  return base64::encode(hash, 20);
}

static void wsSend(const String& text) {
  if (!s_wsOpen) return;
  size_t len = text.length();
  if (len > 125) return;  // 单帧文本帧限 125B(CONTRACT 约定小 JSON)
  uint8_t hdr[2] = {0x81, (uint8_t)(0x80 | len)};  // FIN=1,text;客户端→设备不做掩码(本帧为设备→App)
  s_wsClient.write(hdr, 2);
  s_wsClient.write((const uint8_t*)text.c_str(), len);
}

void khwWsSend(const String& json) { wsSend(json); }

static String s_wsFwVersion = "0.0.0";
void khwWsSetFwVersion(const String& v) { s_wsFwVersion = v; }

static void wsSendHello() {
  String deviceId; khwConfigGet("device", "id", deviceId);
  String hello = String("{\"type\":\"hello\",\"data\":{\"deviceId\":\"") + deviceId +
                 "\",\"fwVersion\":\"" + s_wsFwVersion + "\",\"mode\":\"" + khwStateName() + "\"}}";
  wsSend(hello);
  s_wsHelloSent = true;
}

void khwWsSetup() {
  s_wsServer.begin();
}

// 每轮处理一次:接受新连接(顶替旧)、读帧(握手/心跳/命令)、心跳发送
void khwWsLoop() {
  // 1) 新连接 → 顶替旧
  WiFiClient newc = s_wsServer.accept();
  if (newc) {
    s_wsClient = newc;
    s_wsOpen = false;
    s_wsLastPing = 0;
    s_wsLastHello = 0;
    s_wsHelloSent = false;
  }

  if (!s_wsOpen) {
    if (!s_wsClient) return;
    // 读握手请求
    if (!s_wsClient.available()) return;
    String req = "";
    while (s_wsClient.available()) req += (char)s_wsClient.read();
    // 解析 GET /ws?token=xxx HTTP/1.1
    if (!req.startsWith("GET")) { s_wsClient.stop(); s_wsClient = WiFiClient(); return; }
    // 提取 token 查询参数
    int t = req.indexOf("token=");
    String token = "";
    if (t >= 0) {
      int e = req.indexOf('&', t);
      int sp = req.indexOf(' ', t);
      int end = (e < 0) ? ((sp < 0) ? (int)req.length() : sp) : e;
      if (end > t + 6) token = req.substring(t + 6, end);
    }
    if (!khwTokenCheck(token.c_str())) {
      // 鉴权失败 → 401 关闭
      s_wsClient.print("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
      s_wsClient.stop();
      s_wsClient = WiFiClient();
      return;
    }
    // 找 Sec-WebSocket-Key
    int k = req.indexOf("Sec-WebSocket-Key:");
    if (k < 0) { s_wsClient.stop(); s_wsClient = WiFiClient(); return; }
    int kv = req.indexOf(':', k) + 1;
    int ke = req.indexOf("\r\n", kv);
    String key = req.substring(kv, ke);
    key.trim();
    String accept = wsAccept(key);
    String resp = String("HTTP/1.1 101 Switching Protocols\r\n") +
                  "Upgrade: websocket\r\n" +
                  "Connection: Upgrade\r\n" +
                  "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
    s_wsClient.print(resp);
    s_wsOpen = true;
    s_wsLastHello = 0;
    return;
  }

  // 2) 已开:心跳 + 状态推(由板级 khwWsSend 触发,这里只发 ping)
  uint32_t now = millis();
  if (now - s_wsLastPing >= KHW_WS_PING_INTERVAL_MS) {
    wsSend("{\"type\":\"ping\"}");
    s_wsLastPing = now;
  }
  if (!s_wsHelloSent && now - s_wsLastHello >= KHW_WS_HELLO_INTERVAL_MS) {
    wsSendHello();
    s_wsLastHello = now;
  }

  // 3) 读客户端帧(心跳 pong / action cmd,可选)
  if (s_wsClient.available()) {
    uint8_t hdr[2];
    if (s_wsClient.read(hdr, 2) != 2) { /* 帧不完整,忽略 */ }
    uint8_t op = hdr[0] & 0x0F;
    bool masked = (hdr[1] & 0x80) != 0;
    uint8_t len = hdr[1] & 0x7F;
    if (masked) { uint8_t mk[4]; s_wsClient.read(mk, 4); }  // 丢弃掩码
    if (len > 0) { uint8_t pay[125]; s_wsClient.read(pay, len); }
    if (op == 0x8) {  // close
      s_wsClient.stop();
      s_wsClient = WiFiClient();
      s_wsOpen = false;
    }
  }
}