// lib/common:配网状态机(CONTRACT §1 §7 §8)
// BLE 优先 + SoftAP 兜底;长按 BOOT(GPIO0)4s 清凭据回 SoftAP(M1 逻辑保留)。
// 模式 LED 同步:BLE_READY/SOFTAP→PROVISION_READY, BLE_CONNECTED→PROVISIONING,
//              STA_CONNECTING→CONNECTING, ONLINE→ONLINE, RESET→RESET。
#include "lib_common.h"

#if !defined(KHW_BOOT_PIN)
#define KHW_BOOT_PIN 0
#endif

static ProvisionState s_prov = ProvisionState::IDLE;
static uint32_t s_bootPressStart = 0;
static bool s_bootPressed = false;
// STA_CONNECTING 超时起点:文件级 static,由 khwProvisionSet 进入该态时重置。
// 不能用 case 内 static(BLE 重配网再次进入时首见才初始化,会拿到陈旧 t0 立即超时)。
static uint32_t s_staT0 = 0;

const char* khwStateName() {
  switch (s_prov) {
    case ProvisionState::IDLE:           return "online";
    case ProvisionState::BLE_READY:      return "provision";
    case ProvisionState::BLE_CONNECTED:  return "provision";
    case ProvisionState::STA_CONNECTING: return "connecting";
    case ProvisionState::SOFTAP:         return "provision";
  }
  return "unknown";
}

void khwProvisionSet(ProvisionState s) {
  if (s_prov == s) return;
  s_prov = s;
  switch (s) {
    case ProvisionState::IDLE:           KhwModeLed::set(ModeLedState::ONLINE);          break;
    case ProvisionState::BLE_READY:
    case ProvisionState::SOFTAP:         KhwModeLed::set(ModeLedState::PROVISION_READY); break;
    case ProvisionState::BLE_CONNECTED:  KhwModeLed::set(ModeLedState::PROVISIONING);    break;
    case ProvisionState::STA_CONNECTING:
      s_staT0 = millis();   // 每次进入该态都重起 15s 超时(修 BLE 重配网即时超时 bug)
      KhwModeLed::set(ModeLedState::CONNECTING);
      break;
  }
}

static void clearCredsAndRestart() {
  KhwModeLed::set(ModeLedState::RESET);
  Preferences p;
  if (p.begin("wifi", false)) { p.remove("ssid"); p.remove("pass"); p.end(); }
  if (p.begin("sec", false))  { p.remove("token"); p.end(); }
  delay(1000);
  ESP.restart();
}

void khwProvisionSetup() {
  String ssid, pass;
  bool hasCreds = khwConfigGet("wifi", "ssid", ssid) && ssid.length() > 0;
  if (hasCreds) {
    // 已有凭据:直接 STA_CONNECTING;失败回 SOFTAP(兜底)
    khwProvisionSet(ProvisionState::STA_CONNECTING);
    WiFi.mode(WIFI_STA);
    WiFi.begin(ssid.c_str(), pass.length() ? pass.c_str() : nullptr);
    return;
  }
  // 无凭据:BLE 优先。BLE 初始化慢,稍后由 loop 拉 BLE_READY;
  // BLE 连接失败 → SOFTAP 兜底。
  khwProvisionSet(ProvisionState::BLE_READY);
}

void khwProvisionLoop() {
  uint32_t now = millis();
  // ---- BOOT 4s 检测(独立于 /stream:摄像头板在独立任务里也调本函数)----
  if (digitalRead(KHW_BOOT_PIN) == LOW) {
    if (!s_bootPressed) { s_bootPressed = true; s_bootPressStart = now; }
    else if (now - s_bootPressStart >= 4000) {
      s_bootPressed = false;
      clearCredsAndRestart();
    }
  } else {
    s_bootPressed = false;
  }

  switch (s_prov) {
    case ProvisionState::IDLE: break;
    case ProvisionState::BLE_READY:
    case ProvisionState::SOFTAP:
      // BLE 广播 / SoftAP 已在板级 setup 启动;这里只维持 LED
      break;
    case ProvisionState::BLE_CONNECTED:
      // BLE 收凭据后由 ble.cpp 转 STA_CONNECTING
      break;
    case ProvisionState::STA_CONNECTING: {
      // 连接超时(15s)→ 清 NVS → SOFTAP 兜底
      if (WiFi.status() == WL_CONNECTED) {
        khwProvisionSet(ProvisionState::IDLE);
      } else if (now - s_staT0 >= 15000) {
        s_staT0 = now;
        clearCredsAndRestart();
      }
      break;
    }
  }
}
