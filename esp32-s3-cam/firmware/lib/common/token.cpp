// lib/common:共享令牌(CONTRACT §2)
// 16 位十六进制小写,由 esp_random() 派生;写 NVS sec/token;校验恒时比较。
#include "lib_common.h"
#include "esp_random.h"

static String g_token;  // 缓存,避免每次校验读 NVS

static char hexNibble(uint8_t b) {
  b &= 0x0F;
  return (b < 10) ? ('0' + b) : ('a' + b - 10);
}

bool khwTokenEnsure() {
  Preferences p;
  if (!p.begin("sec", false)) return false;
  String t = p.getString("token", "");
  if (t.length() != 16) {
    t = "";
    for (int i = 0; i < 16; ++i) {
      uint8_t b = (uint8_t)esp_random();
      t += hexNibble(b >> 4);
      t += hexNibble(b);
    }
    p.putString("token", t);
  }
  g_token = t;
  p.end();
  return g_token.length() == 16;
}

const char* khwTokenGet() {
  if (g_token.length() != 16) khwTokenEnsure();
  return g_token.c_str();
}

bool khwTokenCheck(const char* token) {
  if (!token) return false;
  const char* ref = khwTokenGet();
  if (strlen(token) != 16 || strlen(ref) != 16) return false;
  uint8_t diff = 0;
  for (int i = 0; i < 16; ++i) diff |= (uint8_t)(token[i] ^ ref[i]);
  return diff == 0;
}
