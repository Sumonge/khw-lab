// lib/common:BLE 配网(CONTRACT §7)
// 服务 UUID:KHW_CAM / KHW_LOCK(每板一个,由 KHW_SERVICE_TYPE 选择);特征 SSID/PASS/STATUS 共用。
// 流程:App 连接 → 写 SSID → 写 PASS → 设备存 NVS 连 WiFi → STATUS Notify:
//   {"status":"connecting"} / {"status":"ok","token":"...","host":"..."} / {"status":"fail","error":"wifi"}
//   ok 后 1s 重启入网;fail 清 NVS 回 PROVISION_READY 重新广播。
#include "lib_common.h"
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLEAdvertising.h>
#include <BLECharacteristic.h>
#include <BLE2902.h>

#ifndef KHW_BLE_CONNECT_TIMEOUT_MS
#define KHW_BLE_CONNECT_TIMEOUT_MS 15000
#endif

static BLECharacteristic* s_statusChar = nullptr;
static String s_recvSsid;
static String s_recvPass;
static bool   s_provisioning = false;   // 已收到凭据,在等入网结果
static uint32_t s_connStart = 0;
static bool   s_resultSent = false;
// NimBLE 版 BLECharacteristic 无 getNotify();订阅状态由 onSubscribe/onStatus 回调维护
static bool   s_statusNotifyEnabled = false;

static void notifyStatus(const String& json) {
  if (!s_statusChar) return;
  s_statusChar->setValue((uint8_t*)json.c_str(), json.length());
  if (s_statusNotifyEnabled) s_statusChar->notify();
}

// STATUS 特征的订阅回调:NimBLE 通过 onSubscribe 报 CCCD 订阅位,Bluedroid 走 onStatus。
// 不依赖 getNotify(),使 notifyStatus 只在 App 已订阅时发。
class KhwStatusCallbacks : public BLECharacteristicCallbacks {
#if defined(CONFIG_NIMBLE_ENABLED)
  void onSubscribe(BLECharacteristic* /*p*/, ble_gap_conn_desc* /*desc*/, uint16_t subValue) override {
    // CCCD 值: bit0 = NOTIFY 使能, bit1 = INDICATE 使能
    s_statusNotifyEnabled = (subValue & 0x0001) != 0;
  }
#endif
  void onStatus(BLECharacteristic* /*p*/, Status s, uint32_t /*code*/) override {
    if (s == SUCCESS_NOTIFY)      s_statusNotifyEnabled = true;
    else if (s == ERROR_NO_SUBSCRIBER || s == ERROR_NOTIFY_DISABLED)
      s_statusNotifyEnabled = false;
  }
};

// 板级 setup 需要时可选地读 STATUS(免连接,App 已断) —— 见 khwBleReadStatus

class KhwBleServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* srv) override {
    khwProvisionSet(ProvisionState::BLE_CONNECTED);
  }
  void onDisconnect(BLEServer* srv) override {
    // 配网中断(用户取消/超时)→ 清暂存凭据,回 BLE_READY 重新广播
    s_recvSsid = ""; s_recvPass = ""; s_provisioning = false; s_resultSent = false;
    if (srv->getAdvertising()) srv->getAdvertising()->start();
    khwProvisionSet(ProvisionState::BLE_READY);
  }
};

class KhwWriteCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    String val = c->getValue().c_str();
    if (c->getUUID().equals(BLEUUID(KHW_UUID_CHAR_SSID))) {
      if (val.length() > 0 && val.length() <= 32) s_recvSsid = val;
    } else if (c->getUUID().equals(BLEUUID(KHW_UUID_CHAR_PASS))) {
      if (val.length() <= 64) s_recvPass = val;
    }
    // SSID 到位即开始(密码可为空网络)
    if (s_recvSsid.length() > 0 && !s_provisioning) {
      s_provisioning = true;
      s_resultSent = false;
      s_connStart = millis();
      notifyStatus("{\"status\":\"connecting\"}");
      Preferences p;
      if (p.begin("wifi", false)) {
        p.putString("ssid", s_recvSsid);
        p.putString("pass", s_recvPass);
        p.end();
      }
      khwTokenEnsure();
      WiFi.mode(WIFI_STA);
      WiFi.begin(s_recvSsid.c_str(), s_recvPass.c_str());
      khwProvisionSet(ProvisionState::STA_CONNECTING);
    }
  }
};

void khwBleLoop() {
  if (!s_provisioning || s_resultSent) return;
  uint32_t now = millis();
  if (WiFi.status() == WL_CONNECTED) {
    s_resultSent = true;
    String host = WiFi.localIP().toString();
    notifyStatus(String("{\"status\":\"ok\",\"token\":\"") + khwTokenGet() +
                 "\",\"host\":\"" + host + "\"}");
    delay(1000);
    ESP.restart();
  } else if (now - s_connStart >= KHW_BLE_CONNECT_TIMEOUT_MS) {
    s_resultSent = true;
    notifyStatus("{\"status\":\"fail\",\"error\":\"wifi\"}");
    // 清凭据回配网态重新广播
    Preferences p;
    if (p.begin("wifi", false)) { p.remove("ssid"); p.remove("pass"); p.end(); }
    s_recvSsid = ""; s_recvPass = ""; s_provisioning = false;
    khwProvisionSet(ProvisionState::BLE_READY);
  }
}

void khwBleNotifyStatus(const String& json) {
  notifyStatus(json);
}

void khwBleSetup() {
  String deviceId; khwConfigGet("device", "id", deviceId);
  String tail = deviceId.length() >= 4 ? deviceId.substring(deviceId.length() - 4) : deviceId;
  String devName = String(KHW_DEVICE_NAME_PREFIX) + "-" + tail;
  BLEDevice::init(devName.c_str());
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new KhwBleServerCallbacks());
  BLEService* svc = server->createService(KHW_BLE_SVC_UUID);
  BLECharacteristic* ssid = svc->createCharacteristic(
      BLEUUID(KHW_UUID_CHAR_SSID), BLECharacteristic::PROPERTY_WRITE);
  BLECharacteristic* pass = svc->createCharacteristic(
      BLEUUID(KHW_UUID_CHAR_PASS), BLECharacteristic::PROPERTY_WRITE);
  s_statusChar = svc->createCharacteristic(
      BLEUUID(KHW_UUID_CHAR_STATUS),
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  s_statusChar->setCallbacks(new KhwStatusCallbacks());
  // NimBLE 自动管理 2902(CCCD);手动添加已弃用且会产生重复描述符,故不再 new BLE2902()
  ssid->setCallbacks(new KhwWriteCallbacks());
  pass->setCallbacks(new KhwWriteCallbacks());
  svc->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(svc->getUUID());
  adv->setScanResponse(true);
  adv->start();
  // 仅配网态广播;入网后由板级决定是否停广播
}
