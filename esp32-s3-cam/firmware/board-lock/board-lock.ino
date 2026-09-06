// board-lock: XIAO ESP32-C3 电磁锁控制系统(锁板, khw_hub 产品二)
// 硬件: 3V 电磁锁, 仅一根 2P XH2.5 端子(电源+/电源-), 无状态反馈。
//       低边驱动: GPIO3 → AO3400(N-MOS) G 极; 锁负极接 D, 锁正极接 3V。
//       GPIO HIGH → MOS 导通 → 锁得电(锁定); LOW → 释放(开锁)。
//       断电 = 开锁(安全默认)。
// 配网: BLE 优先(KHW-LOCK-xxxx, 服务 6b68776c-...), SoftAP 兜底(KHW-LOCK-Setup),
//       两者并存; 长按 BOOT(GPIO0) 4s 清凭据回配网态。
// 模式 LED: 板载 WS2812(GPIO7), RMT 位 banged, 8 态(见 CONTRACT §6)。
// 协议: 全部复用 lib/common(http/action/ws/ble/provision/token/mode_led)。
// 时间: 入网后 NTP 同步(configTime +8h 时区), status/WS 的 since 用 epoch_ms;
//       未同步(NTP 没起来/还没成功)回退到启动毫秒, 用 1e11 数量级区分。
#define KHW_BOARD_TYPE "lock"
#define KHW_DEVICE_NAME_PREFIX "KHW-LOCK"
#define KHW_SERVICE_TYPE "esp32lock"
#define KHW_IS_LOCK
#define KHW_MODE_LED_WS2812
#define KHW_MODE_LED_PIN 7
#define KHW_BOOT_PIN 0

#include "lib_common.h"
#include <ESPmDNS.h>
#include <time.h>        // time()/time_t(configTime 在 Arduino.h 声明, time.h 需显式)
#include "esp_random.h"

#ifndef KHW_LOCK_PIN
#define KHW_LOCK_PIN 3
#endif
#ifndef KHW_LOCK_AP_NAME
#define KHW_LOCK_AP_NAME "KHW-LOCK-Setup"
#endif
#ifndef KHW_ACTION_COOLDOWN_MS
#define KHW_ACTION_COOLDOWN_MS 150   // 动作总线 busy 互斥冷却(无反馈, GPIO 切换即时完成)
#endif
#ifndef KHW_LOCK_FW_VERSION
#define KHW_LOCK_FW_VERSION "0.2.0"
#endif
// NTP: 默认 UTC+8; 板级可覆盖宏换时区或服务器
#ifndef KHW_TZ_OFFSET_SEC
#define KHW_TZ_OFFSET_SEC 28800
#endif
#ifndef KHW_NTP_SERVER1
#define KHW_NTP_SERVER1 "pool.ntp.org"
#endif
#ifndef KHW_NTP_SERVER2
#define KHW_NTP_SERVER2 "time.nist.gov"
#endif
// 自动回锁: 开锁后 unlockMs 毫秒未再操作则自动锁定; 0 = 不自动回锁(默认, 断电即开锁的安全语义)
#ifndef KHW_UNLOCK_MS_DEFAULT
#define KHW_UNLOCK_MS_DEFAULT 0
#endif

static String   s_deviceId;
static bool     s_locked = false;          // true=锁定(GPIO HIGH, 锁通电)
static uint64_t s_sinceMs = 0;             // 最近状态变更时间(epoch_ms 优先, 未同步=启动 ms)
static uint32_t s_changeMs = 0;            // 最近状态变更的单调启动时刻(自动回锁计时用, 永不用 epoch 相减)
static uint32_t s_unlockMs = 0;            // 自动回锁时长(ms, 0=不自动回锁), NVS lock/unlockMs
static uint32_t s_lastAction = 0;          // 最近动作时刻(互斥)
static bool     s_mdnsStarted = false;
static bool     s_ntpSynced = false;       // NTP 首同步完成后置真(存量 since 换算 epoch_ms)

// ---- 时间原语 ----
// NTP 同步后返回 epoch 毫秒(真值, 16 位 hex token 校验阈值 2023-11-14 附近),
// 未同步返回启动毫秒(毫秒 32bit 足够, 用来区分 epoch/启动两类数值)。
static uint64_t nowMs() {
  time_t t = time(nullptr);
  if (t > 1700000000) return ((uint64_t)t) * 1000ULL;
  return millis();
}

// ---- 锁 IO 原语 ----
static void lockSetHw(bool locked) {
  digitalWrite(KHW_LOCK_PIN, locked ? HIGH : LOW);
  s_locked = locked;
  s_changeMs = millis();
  // NTP 已同步 → 全部走 epoch_ms(含开机恢复); 未同步 → 启动 ms, 首同步时统一换算
  s_sinceMs = s_ntpSynced ? nowMs() : (uint64_t)s_changeMs;
  Preferences p;
  if (p.begin("lock", false)) {
    p.putUChar("state", locked ? 1 : 0);
    p.end();
  }
}

// 契约 §4: {"ok":true,"data":{"state":"locked|unlocked","since":<epoch_ms>}}
static String lockStatusJson() {
  return String("{\"ok\":true,\"data\":{\"state\":\"") + (s_locked ? "locked" : "unlocked") +
         "\",\"since\":" + String((unsigned long long)s_sinceMs) + "}}";
}

// ---- 自动回锁(设置页可配, 0=关闭)----
static void handleSettingsGet() {
  WebServer& srv = khwHttpServer();
  if (!khwHttpAuthOK()) { khwHttpSend401(); return; }
  // 单位:秒(与 POST/App 契约一致); 内部 s_unlockMs 存毫秒
  String json = String("{\"unlockSec\":") + String(s_unlockMs / 1000) + "}";
  srv.send(200, "application/json", json);
}

static void handleSettingsPost() {
  WebServer& srv = khwHttpServer();
  if (!khwHttpAuthOK()) { khwHttpSend401(); return; }
  String body = srv.arg("plain");
  // 值截断到逗号或右花括号再取(与 board-camera handleSettingsPost 同款)
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
  String v;
  if (parseValue("unlockSec", v)) {
    long m = v.toInt();                       // 秒 → 毫秒; <=0 或非法 → 关闭
    if (m < 0) m = 0;
    s_unlockMs = (uint32_t)((uint64_t)m * 1000ULL);
    Preferences p;
    if (p.begin("lock", false)) { p.putULong("unlockMs", s_unlockMs); p.end(); }
  }
  handleSettingsGet();
}

// ---- 动作总线(CONTRACT §4: lock/unlock/toggle/status)----
static String handleAction(const String& action, const String& params) {
  (void)params;
  uint32_t now = millis();
  if (now - s_lastAction < KHW_ACTION_COOLDOWN_MS) {
    return "{\"ok\":false,\"error\":\"busy\"}";
  }
  s_lastAction = now;
  bool before = s_locked;
  if (action == "lock")         lockSetHw(true);
  else if (action == "unlock")  lockSetHw(false);
  else if (action == "toggle")  lockSetHw(!s_locked);
  else if (action != "status")  return "{\"ok\":false,\"error\":\"bad-action\"}";
  // 仅状态变化触发「动作橙短闪」 + WS push(§4/§5); status 轮询/未变化不打扰
  if (s_locked != before) {
    KhwModeLed::set(ModeLedState::ACTION);
    khwWsSend(String("{\"type\":\"status\",\"data\":{\"state\":\"") +
              (s_locked ? "locked" : "unlocked") +
              "\",\"since\":" + String((unsigned long long)s_sinceMs) + "}}");
  }
  return lockStatusJson();
}

// ---- 设备 ID(ESP32 无唯一 MAC 派生, 用随机 hex; NVS 持久化)----
static void ensureDeviceId() {
  if (!khwConfigGet("device", "id", s_deviceId) || s_deviceId.length() == 0) {
    const char* HEXDIGITS = "0123456789abcdef";
    char hx[7];
    for (int i = 0; i < 6; ++i) hx[i] = HEXDIGITS[esp_random() & 0xF];
    hx[6] = 0;
    s_deviceId = String("esp32lock-") + hx;
    khwConfigSet("device", "id", s_deviceId);
  }
}

// ---- mDNS 发现(CONTRACT §3: _esp32lock._tcp; host = deviceId, 与摄像头板一致)----
static void startMdns() {
  MDNS.begin(s_deviceId.c_str());
  MDNS.addService("_esp32lock", "_tcp", 80);
}

void setup() {
  pinMode(KHW_LOCK_PIN, OUTPUT);
  digitalWrite(KHW_LOCK_PIN, LOW);
  KhwModeLed::begin();
  ensureDeviceId();

  khwHttpSetFwVersion(KHW_LOCK_FW_VERSION);
  // 板级路由必须先于 khwHttpSetup() 注册(WebServer 先注册者胜, lib/common 不含 /settings)
  WebServer& srv = khwHttpServer();
  srv.on("/settings", HTTP_GET, handleSettingsGet);
  srv.on("/settings", HTTP_POST, handleSettingsPost);
  khwHttpSetup();
  khwWsSetup();
  khwWsSetFwVersion(KHW_LOCK_FW_VERSION);
  khwActionRegister(handleAction);

  // 恢复持久化锁定状态(开机先 LOW, 若上次锁定则立即补高)与自动回锁配置
  Preferences p;
  uint8_t v = 0;
  uint32_t um = 0;
  if (p.begin("lock", true)) {
    v = p.getUChar("state", 0);
    um = p.getULong("unlockMs", KHW_UNLOCK_MS_DEFAULT);
    p.end();
  }
  s_unlockMs = um;
  s_changeMs = millis();                   // 开机视为一次状态基准(自动回锁从此刻起算)
  if (v != 0) lockSetHw(true);             // 开机恢复不算"变化", 不触发 WS/LED

  String ssid;
  bool hasCreds = khwConfigGet("wifi", "ssid", ssid) && ssid.length() > 0;
  khwProvisionSetup();                     // 内部: 有凭据 → STA; 无凭据 → BLE_READY
  if (!hasCreds) {
    // BLE 优先 + SoftAP 兜底, 两者并存
    khwBleSetup();
    WiFi.mode(WIFI_AP);
    WiFi.softAP(KHW_LOCK_AP_NAME);
    khwProvisionSet(ProvisionState::SOFTAP);
  } else {
    // 有凭据(入网态): 启动 NTP 同步(失败不阻塞, 周期循环里补同步)
    configTime(KHW_TZ_OFFSET_SEC, 0, KHW_NTP_SERVER1, KHW_NTP_SERVER2);
  }
}

void loop() {
  khwProvisionLoop();      // BOOT 4s 检测 + STA 超时兜底
  khwBleLoop();            // BLE 配网结果推进(无凭据态才活跃)
  khwHttpLoop();
  khwWsLoop();
  KhwModeLed::tick();

  // 入网成功后启动 mDNS(配网态不起; 断线自动停, 重连再启)
  if (WiFi.status() == WL_CONNECTED && !s_mdnsStarted) {
    startMdns();
    s_mdnsStarted = true;
  } else if (WiFi.status() != WL_CONNECTED && s_mdnsStarted) {
    s_mdnsStarted = false;
  }

  // NTP 首同步成功后: 把存量启动 ms 换算成 epoch_ms(开机恢复/早期动作的 since)
  if (WiFi.status() == WL_CONNECTED && !s_ntpSynced) {
    time_t t = time(nullptr);
    if (t > 1700000000) {
      s_ntpSynced = true;
      s_sinceMs = ((uint64_t)t) * 1000ULL;
    }
  }

  // 自动回锁: 开锁且未锁定, 超时后自动锁定
  if (s_unlockMs > 0 && !s_locked && (millis() - s_changeMs >= s_unlockMs)) {
    lockSetHw(true);
    KhwModeLed::set(ModeLedState::ACTION);
    khwWsSend(String("{\"type\":\"status\",\"data\":{\"state\":\"locked\",\"since\":") +
              String((unsigned long long)s_sinceMs) + "}}");
  }
}
