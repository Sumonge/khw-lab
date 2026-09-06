// lib/common:配置读写(CONTRACT §8 NVS 键表)
#include "lib_common.h"

bool khwConfigGet(const char* ns, const char* key, String& out) {
  Preferences p;
  if (!p.begin(ns, true)) return false;
  out = p.getString(key, "");
  p.end();
  return true;
}

bool khwConfigSet(const char* ns, const char* key, const String& val) {
  Preferences p;
  if (!p.begin(ns, false)) return false;
  bool ok = p.putString(key, val) > 0;
  p.end();
  return ok;
}
