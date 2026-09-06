// board-lock: XIAO ESP32-C3 电磁锁控制系统(锁板, khw_hub 产品二)
// 硬件: 3V 电磁锁, 仅一根 2P XH2.5 端子(电源+/电源-), 无状态反馈。
//       低边驱动: GPIO3 → AO3400(N-MOS) G 极; 锁负极接 D, 锁正极接 3V。
//       GPIO HIGH → MOS 导通 → 锁得电(锁定); LOW → 释放(开锁)。
//       断电 = 开锁(安全默认)。
// 配网: BLE 优先(KHW-LOCK-xxxx, 服务 6b68776c-...), SoftAP 兜底(KHW-LOCK-Setup),
//       两者并存; 长按 BOOT(GPIO0) 4s 清凭据回配网态。
// 模式 LED: 板载 WS2812(GPIO7), RMT 位 banged, 8 态(见 CONTRACT §6)。
// 协议: 全部复用 lib/common(http/action/ws/ble/provision/token/mode_led)。
#define KHW_BOARD_TYPE "lock"
#define KHW_DEVICE_NAME_PREFIX "KHW-LOCK"
#define KHW_SERVICE_TYPE "esp32lock"
#define KHW_IS_LOCK
#define KHW_MODE_LED_WS2812
#define KHW_MODE_LED_PIN 7
#define KHW_BOOT_PIN 0

#include "lib_common.h"
#include <ESPmDNS.h>
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
#define KHW_LOCK_FW_VERSION "0.1.0"
#endif

static String s_deviceId;
static bool   s_locked = false;       // true=锁定(GPIO HIGH, 锁通电)
static uint32_t s_since = 0;          // 最近状态变更的启动时间戳(ms)
static uint32_t s_lastAction = 0;     // 最近动作时刻(互斥)
static bool   s_mdnsStarted = false;

// ---- 锁 IO 原语 ----
static void lockSetHw(bool locked) {
  digitalWrite(KHW_LOCK_PIN, locked ? HIGH : LOW);
  s_locked = locked;
  s_since = millis();
  Preferences p;
  if (p.begin("lock", false)) { p.putUChar("state", locked ? 1 : 0); p.end(); }
}

static String lockStatusJson() {
  return String("{\"ok\":true,\"state\":\"") + (s_locked ? "locked" : "unlocked") +
         "\",\"since\":" + String(s_since) + "}";
}

// ---- 动作总线(CONTRACT §4: lock/unlock/toggle/status)----
static String handleAction(const String& action, const String& params) {
  uint32_t now = millis();
  if (now - s_lastAction < KHW_ACTION_COOLDOWN_MS) {
    return "{\"ok\":false,\"error\":\"busy\"}";
  }
  s_lastAction = now;
  if (action == "lock")         lockSetHw(true);
  else if (action == "unlock")  lockSetHw(false);
  else if (action == "toggle")  lockSetHw(!s_locked);
  else if (action != "status")  return "{\"ok\":false,\"error\":\"bad-action\"}";
  String resp = lockStatusJson();
  khwWsSend(String("{\"type\":\"status\",\"data\":") + resp + "}");
  return resp;
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

// ---- mDNS 发现(CONTRACT §3: _esp32lock._tcp)----
static void startMdns() {
  String host = String("khw-lock-") + s_deviceId.substring(s_deviceId.length() - 4);
  MDNS.begin(host.c_str());
  MDNS.addService("_esp32lock", "_tcp", 80);
}

void setup() {
  pinMode(KHW_LOCK_PIN, OUTPUT);
  digitalWrite(KHW_LOCK_PIN, LOW);
  KhwModeLed::begin();
  ensureDeviceId();

  khwHttpSetFwVersion(KHW_LOCK_FW_VERSION);
  khwHttpSetup();
  khwWsSetup();
  khwWsSetFwVersion(KHW_LOCK_FW_VERSION);
  khwActionRegister(handleAction);

  // 恢复持久化锁定状态(开机先 LOW, 若上次锁定则立即补高)
  Preferences p;
  uint8_t v = 0;
  if (p.begin("lock", true)) { v = p.getUChar("state", 0); p.end(); }
  if (v != 0) lockSetHw(true);

  String ssid;
  bool hasCreds = khwConfigGet("wifi", "ssid", ssid) && ssid.length() > 0;
  khwProvisionSetup();                     // 内部: 有凭据 → STA; 无凭据 → BLE_READY
  if (!hasCreds) {
    // BLE 优先 + SoftAP 兜底, 两者并存
    khwBleSetup();
    WiFi.mode(WIFI_AP);
    WiFi.softAP(KHW_LOCK_AP_NAME);
    khwProvisionSet(ProvisionState::SOFTAP);
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
}
