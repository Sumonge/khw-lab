// lib/common:模式 LED 状态机(CONTRACT §6)
// 双实现:
//   KHW_MODE_LED_WS2812 → XIAO C3 板载 WS2812(GPIO7),RMT 位 banged(rmtInit/rmtWrite),无第三方库
//   KHW_MODE_LED_GPIO   → 外接单色 LED(编译期宏 KHW_MODE_LED_PIN),digitalWrite + 非阻塞周期翻转
// 所有效果非阻塞(millis 时间片),tick() 并入 loop()。
#include "lib_common.h"

#if defined(KHW_MODE_LED_WS2812)
#include "esp32-hal-rmt.h"
#elif !defined(KHW_MODE_LED_GPIO)
#error "lib/common: set KHW_MODE_LED_WS2812 or KHW_MODE_LED_GPIO in board .ino"
#endif

// ---- WS2812 时序(100ns tick 下的 RMT)----
// 400kHz 数据率,GRB 顺序,24bit/像素:
//   bit1: 高 8 tick + 低 8 tick = 1.6us
//   bit0: 高 4 tick + 低 8 tick = 1.2us
#define KHW_RMT_TICKS_HI_1  8
#define KHW_RMT_TICKS_LO_1  8
#define KHW_RMT_TICKS_HI_0  4
#define KHW_RMT_TICKS_LO_0  8

// 模式 → (r,g,b) 映射(CONTRACT §6 八态表)
struct Rgb { uint8_t r, g, b; };
static const Rgb s_modeColor[8] = {
  {255, 255, 255}, // BOOT            白
  {0,   0,   255}, // PROVISION_READY 蓝
  {0,   0,   255}, // PROVISIONING    蓝
  {255, 255, 0},   // CONNECTING      黄
  {0,   255, 0},   // ONLINE          绿
  {255, 165, 0},   // ACTION          橙
  {255, 0,   0},   // ERROR           红
  {255, 0,   0},   // RESET           红
};
static_assert(sizeof(s_modeColor) / sizeof(s_modeColor[0]) == 8,
              "mode color table must cover all 8 ModeLedState");

// 动作状态:on/off 与周期(ms);ACTION 只闪一次(100ms)
static ModeLedState s_state = ModeLedState::BOOT;
static uint32_t s_cycleMs   = 1000;   // 当前周期
static bool     s_on         = false; // 当前电平
static bool     s_actionOneShotDone = false;
static uint32_t s_lastMs     = 0;

// 构造单个 RMT 符号:高电平 hi 个 tick + 低电平 lo 个 tick(RMT_SYMBOLS_OF 是"个数"宏,不能构造符号)
static rmt_data_t ws2812Bit(uint16_t hi, uint16_t lo) {
  rmt_data_t s;
  s.duration0 = hi;
  s.level0    = 1;
  s.duration1 = lo;
  s.level1    = 0;
  return s;
}

static void applyLevel(bool on) {
#if defined(KHW_MODE_LED_WS2812)
  if (on) {
    const Rgb& c = s_modeColor[(int)s_state];
    // WS2812 发 0x000000 复位:全部关
    rmt_data_t led[24];
    for (int i = 0; i < 24; ++i) led[i] = ws2812Bit(KHW_RMT_TICKS_HI_0, KHW_RMT_TICKS_LO_0);
    rmtWrite(KHW_MODE_LED_PIN, led, 24, 10);
    // 发目标色
    uint32_t grb = ((uint32_t)c.g << 16) | ((uint32_t)c.r << 8) | c.b;
    for (int i = 23; i >= 0; --i) {
      bool bit = (grb >> i) & 1;
      led[23 - i] = ws2812Bit(bit ? KHW_RMT_TICKS_HI_1 : KHW_RMT_TICKS_HI_0,
                              bit ? KHW_RMT_TICKS_LO_1 : KHW_RMT_TICKS_LO_0);
    }
    rmtWrite(KHW_MODE_LED_PIN, led, 24, 10);
  } else {
    rmt_data_t off[24];
    for (int i = 0; i < 24; ++i) off[i] = ws2812Bit(KHW_RMT_TICKS_HI_0, KHW_RMT_TICKS_LO_0);
    rmtWrite(KHW_MODE_LED_PIN, off, 24, 10);
  }
#elif defined(KHW_MODE_LED_GPIO)
  digitalWrite(KHW_MODE_LED_PIN, on ? HIGH : LOW);
#endif
}

void KhwModeLed::begin() {
#if defined(KHW_MODE_LED_WS2812)
  // rmtInit 已在板级 setup 调用过(CONTRACT §6 固定参数);这里保证仅一次
  static bool inited = false;
  if (!inited) {
    rmtInit(KHW_MODE_LED_PIN, RMT_TX_MODE, RMT_MEM_NUM_BLOCKS_1, 10000000);
    inited = true;
  }
#else
  pinMode(KHW_MODE_LED_PIN, OUTPUT);
  digitalWrite(KHW_MODE_LED_PIN, LOW);
#endif
  set(ModeLedState::BOOT);
  s_lastMs = millis();
}

void KhwModeLed::set(ModeLedState s) {
  s_state = s;
  s_actionOneShotDone = false;
  switch (s) {
    case ModeLedState::BOOT:             s_cycleMs = 300;  break; // 快闪3次
    case ModeLedState::PROVISION_READY:  s_cycleMs = 1500; break; // 呼吸慢闪
    case ModeLedState::PROVISIONING:     s_cycleMs = 0;    break; // 常亮
    case ModeLedState::CONNECTING:       s_cycleMs = 200;  break; // 快闪
    case ModeLedState::ONLINE:           s_cycleMs = 0;    break; // 常亮
    case ModeLedState::ACTION:           s_cycleMs = 100;  break; // 短闪1次
    case ModeLedState::ERROR:            s_cycleMs = 1000; break; // 慢闪
    case ModeLedState::RESET:            s_cycleMs = 200;  break; // 快闪
  }
  s_on = true;
  applyLevel(true);
  s_lastMs = millis();
}

void KhwModeLed::tick() {
  uint32_t now = millis();
  if (s_cycleMs == 0) return; // 常亮/常灭无刷新
  if (s_state == ModeLedState::ACTION) {
    if (s_actionOneShotDone) return;
    // 100ms 亮 → 灭,只一次
    if (s_on && now - s_lastMs >= 100) {
      s_on = false;
      applyLevel(false);
      s_lastMs = now;
      s_actionOneShotDone = true;
    }
    return;
  }
  // 周期翻转(on 占 50%)
  if (now - s_lastMs >= s_cycleMs / 2) {
    s_on = !s_on;
    applyLevel(s_on);
    s_lastMs = now;
  }
}
