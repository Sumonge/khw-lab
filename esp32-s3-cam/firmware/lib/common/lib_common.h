#pragma once
// lib/common 骨架头 —— 所有板(ESP32-S3-CAM 摄像头板 / XIAO ESP32-C3 锁板)共用。
// 本文件是 M2 契约(CONTRACT.md)的实现骨架:
//   §2 共享令牌  §4 动作总线  §6 模式LED  §7 BLE配网  §8 NVS
// 语法校验脚本(_check_syntax_c3.sh / _check_syntax.sh)都会 include 本文件,
// 因此本文件必须能被 32bit 体系(xtensa/rv32)同时编译通过,勿引用板级专属头。

#include <Arduino.h>
#include <Preferences.h>
#include <WiFi.h>
#include <WebServer.h>

// ---- 板级编译期宏(每板在 .ino 顶部 define,lib/common 只读,不设默认,缺省即报错)----
#ifndef KHW_BOARD_TYPE        // "camera" | "lock"
#error "lib/common: KHW_BOARD_TYPE not defined (set in board .ino)"
#endif
#ifndef KHW_DEVICE_NAME_PREFIX  // 设备名形如 KHW-CAM-xxxx / KHW-LOCK-xxxx
#error "lib/common: KHW_DEVICE_NAME_PREFIX not defined"
#endif
#ifndef KHW_SERVICE_TYPE       // mDNS: "esp32cam" | "esp32lock"
#error "lib/common: KHW_SERVICE_TYPE not defined"
#endif

// BLE 配网服务 UUID(两板共用特征,服务 UUID 区分产品 —— CONTRACT §7)
#define KHW_SVC_UUID_BASE      "0000-1000-8000-00805f9b34fb"
#define KHW_UUID_CAM_SERVICE   "6b687763" KHW_SVC_UUID_BASE   // "khw" + 'c'
#define KHW_UUID_LOCK_SERVICE  "6b68776c" KHW_SVC_UUID_BASE   // "khw" + 'l'
#define KHW_UUID_CHAR_SSID     "6b6877d1" KHW_SVC_UUID_BASE
#define KHW_UUID_CHAR_PASS     "6b6877d2" KHW_SVC_UUID_BASE
#define KHW_UUID_CHAR_STATUS   "6b6877d3" KHW_SVC_UUID_BASE

// 板级选择:锁板在 .ino 顶部 #define KHW_IS_LOCK → 广播 KHW_UUID_LOCK_SERVICE;
// 摄像头板不定义 → 走 KHW_UUID_CAM_SERVICE。不能用 strcmp(预处理器非法)。
#if defined(KHW_IS_LOCK)
#define KHW_BLE_SVC_UUID KHW_UUID_LOCK_SERVICE
#else
#define KHW_BLE_SVC_UUID KHW_UUID_CAM_SERVICE
#endif

// ---- 模式 LED 状态机(CONTRACT §6 八态)----
enum class ModeLedState : uint8_t {
  BOOT,             // 白  快闪3次
  PROVISION_READY,  // 蓝  呼吸慢闪(1.5s)
  PROVISIONING,     // 蓝  常亮
  CONNECTING,       // 黄  快闪(200ms)
  ONLINE,           // 绿  常亮
  ACTION,           // 橙  短闪1次(100ms)
  ERROR,            // 红  慢闪(1s)
  RESET,            // 红  快闪(200ms)
};

// ---- 配网状态机(BLE 优先 + SoftAP 兜底,CONTRACT §7)----
enum class ProvisionState : uint8_t {
  IDLE,          // 已入网(STA 运行态)
  BLE_READY,     // 无凭据 → BLE 广播中(蓝呼吸)
  BLE_CONNECTED, // BLE 已收到凭据(蓝常亮)
  STA_CONNECTING,// 凭据已存,连 WiFi 中(黄快闪)
  SOFTAP,        // BLE 兜底失败 → SoftAP(蓝呼吸)
};

// ---- 共享令牌(CONTRACT §2)----
bool    khwTokenEnsure();                     // 无则生成 16hex 写 NVS sec/token,返回是否有
const char* khwTokenGet();                    // 返回 NVS 中 token(空串若无)
bool    khwTokenCheck(const char* token);     // 恒时比较(长度 + 逐字符)

// ---- 动作总线(CONTRACT §4)----
// 板级在 setup() 调 khwActionRegister,lib/common 收到 POST /action 后派发。
typedef String (*KhwActionHandler)(const String& action, const String& paramsJson);
void khwActionRegister(KhwActionHandler handler);
String khwActionDispatch(const String& raw);   // http.cpp 鉴权后调用

// ---- NVS 配置(CONTRACT §8)----
bool khwConfigGet(const char* ns, const char* key, String& out);
bool khwConfigSet(const char* ns, const char* key, const String& val);

// ---- 模式 LED 驱动实现选择 ----
// 板级在 .ino 顶部定义其一:
//   KHW_MODE_LED_WS2812  → rmtInit/rmtWrite 位 banged(WS2812, C3 板载 GPIO7)
//   KHW_MODE_LED_GPIO    → digitalWrite + 非阻塞周期翻转(外接单色 LED)
#if !defined(KHW_MODE_LED_WS2812) && !defined(KHW_MODE_LED_GPIO)
#error "lib/common: define KHW_MODE_LED_WS2812 or KHW_MODE_LED_GPIO in board .ino"
#endif
#ifndef KHW_MODE_LED_PIN
#error "lib/common: KHW_MODE_LED_PIN not defined (set in board .ino)"
#endif

class KhwModeLed {
public:
  static void begin();
  static void set(ModeLedState s);
  static void tick();            // 非阻塞刷新,并入 loop()
};

// ---- BLE 配网 ----
void khwBleSetup();
void khwBleLoop();
void khwBleNotifyStatus(const String& json);  // ble.cpp 内公开给 provision.cpp 推送

// ---- WebSocket 基础(CONTRACT §5)----
void khwWsSetup();
void khwWsLoop();                // 非阻塞;握手+心跳+单客户端顶替,状态变化推 status
void khwWsSend(const String& json);          // 设备→App 推送(CONTRACT §4 动作结果)
void khwWsSetFwVersion(const String& v);     // 板级在 setup 设固件版本(hello 帧带)

// ---- 配网状态机 ----
enum class ProvisionState : uint8_t;
void khwProvisionSet(ProvisionState s);
void khwProvisionSetup();        // 按 ProvisionState 初始化(BLE 广播 / SoftAP)
void khwProvisionLoop();         // 非阻塞状态机推进,含 BOOT 4s 回 SoftAP 检测
const char* khwStateName();      // "online" | "provision" | "connecting"

// ---- HTTP 底座 ----
void khwHttpSetup();             // 注册 /status /config /unpair + 动作总线 /action
void khwHttpLoop();              // handleClient 分发
void khwHttpSetFwVersion(const String& v);  // 板级在 setup 设固件版本
WebServer& khwHttpServer();      // 返回共享的 WebServer 实例(板级注册专属路由,同端口 80)
bool   khwHttpAuthOK();          // 板级处理器用:校验 X-Khw-Token 头
void   khwHttpSend401();         // 板级处理器用:401 {"ok":false,"error":"unauthorized"}
