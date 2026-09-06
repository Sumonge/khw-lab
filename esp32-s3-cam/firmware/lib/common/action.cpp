// lib/common:动作总线(CONTRACT §4)
// 板级把动作处理器注册进来;POST /action 在 http.cpp 鉴权后派发到这里。
#include "lib_common.h"

static KhwActionHandler s_handler = nullptr;

void khwActionRegister(KhwActionHandler handler) {
  s_handler = handler;
}

// 由 http.cpp 在鉴权通过后调用:
//   in:  "{\"action\":\"<name>\",\"params\":{...}}"
//   out: 成功 "{\"ok\":true,\"data\":{...}}" ;失败 "{\"ok\":false,\"error\":\"<code>\"}"
String khwActionDispatch(const String& raw) {
  if (!s_handler) return F("{\"ok\":false,\"error\":\"bad-action\"}");
  // 极简 JSON 解析:抠出 "action":"..." 与 "params":{...} 两段(板级 params 原样透传)
  String action = "";
  String params = "{}";
  int i = raw.indexOf(F("\"action\""));
  if (i >= 0) {
    int c1 = raw.indexOf('"', i + 8);
    int c2 = (c1 >= 0) ? raw.indexOf('"', c1 + 1) : -1;
    if (c1 >= 0 && c2 > c1) action = raw.substring(c1 + 1, c2);
  }
  int j = raw.indexOf(F("\"params\""));
  if (j >= 0) {
    int o = raw.indexOf('{', j);
    int d = raw.indexOf('}', o);
    if (o >= 0 && d > o) params = raw.substring(o, d + 1);
  }
  if (action.length() == 0) return F("{\"ok\":false,\"error\":\"bad-action\"}");
  return s_handler(action, params);
}
