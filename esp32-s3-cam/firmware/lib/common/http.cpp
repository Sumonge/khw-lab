// lib/common:HTTP 底座(CONTRACT §1 §2 §4)
// 端点: /status(免令牌) /config(免令牌,SoftAP) /action(令牌) /unpair(令牌)
//       /settings(令牌) 由板级覆盖; /stream(令牌,摄像头板)
// 令牌校验失败一律 401 {"ok":false,"error":"unauthorized"}。
#include "lib_common.h"

#ifndef KHW_HTTP_PORT
#define KHW_HTTP_PORT 80
#endif

static WebServer s_server(KHW_HTTP_PORT);
static String s_fwVersion = "0.0.0";

void khwHttpSetFwVersion(const String& v) { s_fwVersion = v; }

static bool authOK(WebServer& srv) {
  if (!srv.hasHeader("X-Khw-Token")) return false;
  return khwTokenCheck(srv.header("X-Khw-Token").c_str());
}

static void send401(WebServer& srv) {
  srv.send(401, "application/json", F("{\"ok\":false,\"error\":\"unauthorized\"}"));
}

// ---- 板级公开访问器(摄像头板在同一 80 端口注册专属路由: / /settings /stream)----
WebServer& khwHttpServer() { return s_server; }
bool khwHttpAuthOK()       { return authOK(s_server); }
void khwHttpSend401()      { send401(s_server); }

// GET /status —— 免令牌:身份/健康探测(CONTRACT §1;token 仅配网响应带)
static void hStatus() {
  String deviceId; khwConfigGet("device", "id", deviceId);
  String body = String(F("{\"id\":\"")) + deviceId
              + F(",\"name\":\"") + deviceId
              + F("\",\"type\":\"") + KHW_SERVICE_TYPE
              + F("\",\"fwVersion\":\"") + s_fwVersion
              + F("\",\"mode\":\"") + (WiFi.status() == WL_CONNECTED ? "online" : "provision")
              + F("\"}");
  s_server.send(200, "application/json", body);
}

// POST /config —— 免令牌:配网态 SoftAP 收凭据(CONTRACT §1 §2)
// body {ssid,password} → 存 NVS → 生成 token → 重启;响应 {ok,deviceId,token}
static void hConfig() {
  String raw = s_server.arg("plain");
  String ssid = "", pass = "";
  int i = raw.indexOf(F("\"ssid\""));
  if (i >= 0) {
    int c1 = raw.indexOf('"', i + 7);
    int c2 = (c1 >= 0) ? raw.indexOf('"', c1 + 1) : -1;
    if (c1 >= 0 && c2 > c1) ssid = raw.substring(c1 + 1, c2);
  }
  int j = raw.indexOf(F("\"password\""));
  if (j >= 0) {
    int c1 = raw.indexOf('"', j + 10);
    int c2 = (c1 >= 0) ? raw.indexOf('"', c1 + 1) : -1;
    if (c1 >= 0 && c2 > c1) pass = raw.substring(c1 + 1, c2);
  }
  if (ssid.length() == 0 || ssid.length() > 32) {
    s_server.send(400, "application/json", F("{\"ok\":false,\"error\":\"bad-ssid\"}"));
    return;
  }
  if (!khwConfigSet("wifi", "ssid", ssid)) {
    s_server.send(500, "application/json", F("{\"ok\":false,\"error\":\"nvs\"}"));
    return;
  }
  khwConfigSet("wifi", "pass", pass);
  khwTokenEnsure();  // 生成/保证 token
  String deviceId; khwConfigGet("device", "id", deviceId);
  String body = String(F("{\"ok\":true,\"deviceId\":\"")) + deviceId
              + F("\",\"token\":\"") + khwTokenGet() + F("\"}");
  s_server.send(200, "application/json", body);
  delay(1000);
  ESP.restart();
}

// POST /action —— 令牌:动作总线派发(CONTRACT §4)
static void hAction() {
  if (!authOK(s_server)) { send401(s_server); return; }
  String resp = khwActionDispatch(s_server.arg("plain"));
  s_server.send(200, "application/json", resp);
}

// POST /unpair —— 令牌:清 WiFi 凭据 + 清 token → 重启回配网态(CONTRACT §1)
static void hUnpair() {
  if (!authOK(s_server)) { send401(s_server); return; }
  Preferences p;
  if (p.begin("wifi", false)) { p.remove("ssid"); p.remove("pass"); p.end(); }
  if (p.begin("sec", false)) { p.remove("token"); p.end(); }
  KhwModeLed::set(ModeLedState::RESET);
  s_server.send(200, "application/json", F("{\"ok\":true}"));
  delay(1000);
  ESP.restart();
}

void khwHttpSetup() {
  s_server.on("/status", HTTP_GET, hStatus);
  s_server.on("/config", HTTP_POST, hConfig);
  s_server.on("/action", HTTP_POST, hAction);
  s_server.on("/unpair", HTTP_POST, hUnpair);
  // /settings /stream 由板级注册(各板专属逻辑),这里只保留底座
  s_server.begin();
}

void khwHttpLoop() {
  s_server.handleClient();
}
