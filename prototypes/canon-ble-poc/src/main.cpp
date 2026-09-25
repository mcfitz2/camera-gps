// Proof of concept: pair with a Canon EOS (smartphone BLE mode) and feed it
// GPS coordinates + time. Controlled over serial at 115200 baud.
//
// Protocol based on furble (https://github.com/gkoh/furble) CanonEOSSmart.
//
// Commands:
//   scan                 scan 10s for Canon cameras in pairing/advertising mode
//   pair <n>             pair with scan result n (confirm on camera)
//   connect              reconnect to saved camera
//   disconnect
//   forget               delete saved camera and all bonds
//   gps <lat> <lon> [alt] set coordinates (decimal degrees, metres)
//   time <epoch>         set current UTC time (unix seconds)
//   send                 send location+time now
//   auto <sec>           auto-send interval, 0 disables
//   shutter              trigger shutter (connection sanity check)
//   gatt                 dump camera services/characteristics
//   status

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Preferences.h>
#include <esp_mac.h>
#include <vector>

static const NimBLEUUID PRI_SVC_UUID(0x00010000, 0x0000, 0x1000, 0x0000d8492fffa821);
static const NimBLEUUID CHR_NAME_UUID(0x00010006, 0x0000, 0x1000, 0x0000d8492fffa821);
static const NimBLEUUID CHR_IDEN_UUID(0x0001000a, 0x0000, 0x1000, 0x0000d8492fffa821);
static const NimBLEUUID SVC_MODE_UUID(0x00030000, 0x0000, 0x1000, 0x0000d8492fffa821);
static const NimBLEUUID CHR_MODE_UUID(0x00030010, 0x0000, 0x1000, 0x0000d8492fffa821);
static const NimBLEUUID CHR_SHUTTER_UUID(0x00030030, 0x0000, 0x1000, 0x0000d8492fffa821);
static const NimBLEUUID GEO_SVC_UUID(0x00040000, 0x0000, 0x1000, 0x0000d8492fffa821);
static const NimBLEUUID GEO_CHR_UUID(0x00040002, 0x0000, 0x1000, 0x0000d8492fffa821);
static const NimBLEUUID GEO_IND_UUID(0x00040003, 0x0000, 0x1000, 0x0000d8492fffa821);

static constexpr uint16_t CANON_COMPANY_ID = 0x01a9;
static constexpr uint8_t PAIR_ACCEPT = 0x02;
static constexpr uint8_t MODE_SHOOT = 0x02;
static constexpr uint8_t GEO_REQUEST = 0x03;
static constexpr uint8_t GEO_SUCCESS = 0x02;

typedef struct __attribute__((packed)) {
  uint8_t header;               // 0x04
  uint8_t latitude_direction;   // 'N' or 'S'
  float latitude;
  uint8_t longitude_direction;  // 'E' or 'W'
  float longitude;
  uint8_t elevation_sign;       // '+' or '-'
  float elevation;
  uint32_t timestamp;           // unix seconds, UTC
} canon_geo_t;

Preferences prefs;
NimBLEClient *client = nullptr;
NimBLERemoteCharacteristic *geoChr = nullptr;
std::vector<NimBLEAddress> scanResults;

char deviceName[20];
uint8_t deviceUuid[16];

volatile uint8_t pairResult = 0;
volatile bool geoRequested = false;
volatile bool geoEnabled = false;

double lat = 41.8781, lon = -87.6298, alt = 181.0;  // bogus default: Chicago
uint32_t epochBase = 0;                              // 0 = time not set
uint32_t epochSetAtMs = 0;
uint32_t autoSendSec = 10;
uint32_t lastSendMs = 0;

static void hexdump(const char *label, const uint8_t *data, size_t len) {
  Serial.printf("%s [%u]:", label, (unsigned)len);
  for (size_t i = 0; i < len; i++) Serial.printf(" %02x", data[i]);
  Serial.println();
}

static uint32_t nowEpoch() {
  if (epochBase == 0) return 0;
  return epochBase + (millis() - epochSetAtMs) / 1000;
}

class ClientCallbacks : public NimBLEClientCallbacks {
  void onConnect(NimBLEClient *c) override { Serial.println("[ble] connected"); }
  void onDisconnect(NimBLEClient *c, int reason) override {
    Serial.printf("[ble] disconnected, reason=0x%x\n", reason);
    geoEnabled = false;
    geoChr = nullptr;
  }
  void onConfirmPasskey(NimBLEConnInfo &info, uint32_t pin) override {
    Serial.printf("[ble] confirm passkey %06lu -> yes\n", (unsigned long)pin);
    NimBLEDevice::injectConfirmPasskey(info, true);
  }
  void onAuthenticationComplete(NimBLEConnInfo &info) override {
    Serial.printf("[ble] auth complete: encrypted=%d bonded=%d\n", info.isEncrypted(), info.isBonded());
  }
} clientCallbacks;

static bool writePrefix(const NimBLEUUID &chr, uint8_t prefix, const void *data, size_t len) {
  uint8_t buf[len + 1];
  buf[0] = prefix;
  memcpy(&buf[1], data, len);
  return client->setValue(PRI_SVC_UUID, chr, NimBLEAttValue(buf, len + 1));
}

static void geoIndication(NimBLERemoteCharacteristic *, uint8_t *data, size_t len, bool isNotify) {
  hexdump("[geo] indication", data, len);
  if (len == 0) return;
  if (data[0] == GEO_REQUEST) geoRequested = true;
  if (data[0] == GEO_SUCCESS) geoEnabled = true;
}

static void pairIndication(NimBLERemoteCharacteristic *, uint8_t *data, size_t len, bool isNotify) {
  hexdump("[pair] indication", data, len);
  if (!isNotify && len > 0) pairResult = data[0];
}

static bool connectCamera(const NimBLEAddress &addr) {
  if (client && client->isConnected()) {
    Serial.println("already connected");
    return true;
  }
  if (!client) {
    client = NimBLEDevice::createClient();
    client->setClientCallbacks(&clientCallbacks, false);
    client->setConnectTimeout(10000);
  }

  bool bonded = NimBLEDevice::isBonded(addr);
  pairResult = bonded ? PAIR_ACCEPT : 0;
  geoRequested = geoEnabled = false;

  Serial.printf("connecting to %s (bonded=%d)...\n", addr.toString().c_str(), bonded);
  if (!client->connect(addr)) {
    Serial.println("connect failed");
    return false;
  }

  Serial.println("securing...");
  if (!client->secureConnection()) {
    Serial.println("secure failed");
    client->disconnect();
    return false;
  }

  NimBLERemoteService *svc = client->getService(PRI_SVC_UUID);
  if (!svc) {
    Serial.println("primary service not found");
    client->disconnect();
    return false;
  }
  NimBLERemoteCharacteristic *nameChr = svc->getCharacteristic(CHR_NAME_UUID);
  if (nameChr && nameChr->canIndicate()) nameChr->subscribe(false, pairIndication);

  Serial.println("identifying...");
  size_t nameLen = strlen(deviceName);
  uint8_t mode = 0x02;
  if (!writePrefix(CHR_NAME_UUID, 0x01, deviceName, nameLen) ||
      !writePrefix(CHR_IDEN_UUID, 0x03, deviceUuid, sizeof(deviceUuid)) ||
      !writePrefix(CHR_IDEN_UUID, 0x04, deviceName, nameLen) ||
      !writePrefix(CHR_IDEN_UUID, 0x05, &mode, 1)) {
    Serial.println("identify write failed");
    client->disconnect();
    return false;
  }

  if (pairResult != PAIR_ACCEPT) {
    Serial.println(">>> confirm pairing on the camera (60s) <<<");
    for (int i = 0; i < 60 && pairResult == 0; i++) delay(1000);
    if (pairResult != PAIR_ACCEPT) {
      Serial.printf("pairing rejected/timeout (0x%02x)\n", pairResult);
      NimBLEDevice::deleteBond(addr);
      client->disconnect();
      return false;
    }
  }

  NimBLERemoteService *geoSvc = client->getService(GEO_SVC_UUID);
  if (geoSvc) {
    geoChr = geoSvc->getCharacteristic(GEO_CHR_UUID);
    NimBLERemoteCharacteristic *ind = geoSvc->getCharacteristic(GEO_IND_UUID);
    if (ind) {
      bool ok = ind->subscribe(false, geoIndication);
      Serial.printf("subscribed to geo indications: %d\n", ok);
    }
  } else {
    Serial.println("WARNING: geo service not found");
  }

  uint8_t done = 0x01;
  if (!client->setValue(PRI_SVC_UUID, CHR_IDEN_UUID, NimBLEAttValue(&done, 1))) {
    Serial.println("final identify write failed");
    client->disconnect();
    return false;
  }
  client->setValue(SVC_MODE_UUID, CHR_MODE_UUID, NimBLEAttValue(&MODE_SHOOT, 1));

  prefs.putString("addr", addr.toString().c_str());
  prefs.putUChar("type", addr.getType());
  Serial.println("paired + connected. waiting for camera to request location...");
  return true;
}

static void sendGeo() {
  if (!client || !client->isConnected()) {
    Serial.println("not connected");
    return;
  }
  if (!geoChr) {
    Serial.println("no geo characteristic");
    return;
  }
  if (epochBase == 0) {
    Serial.println("time not set, use: time <epoch>");
    return;
  }
  canon_geo_t geo = {
      .header = 0x04,
      .latitude_direction = (uint8_t)(lat < 0 ? 'S' : 'N'),
      .latitude = (float)fabs(lat),
      .longitude_direction = (uint8_t)(lon < 0 ? 'W' : 'E'),
      .longitude = (float)fabs(lon),
      .elevation_sign = (uint8_t)(alt < 0 ? '-' : '+'),
      .elevation = (float)fabs(alt),
      .timestamp = nowEpoch(),
  };
  bool ok = geoChr->writeValue((const uint8_t *)&geo, sizeof(geo), true);
  Serial.printf("[geo] sent lat=%.6f lon=%.6f alt=%.1f t=%lu enabled=%d ok=%d\n", lat, lon, alt,
                (unsigned long)geo.timestamp, geoEnabled, ok);
  hexdump("[geo] payload", (const uint8_t *)&geo, sizeof(geo));
}

static void doScan() {
  scanResults.clear();
  Serial.println("scanning 10s...");
  NimBLEScan *scan = NimBLEDevice::getScan();
  scan->setActiveScan(true);
  NimBLEScanResults results = scan->getResults(10000, false);
  for (int i = 0; i < results.getCount(); i++) {
    const NimBLEAdvertisedDevice *dev = results.getDevice(i);
    bool smart = dev->isAdvertisingService(PRI_SVC_UUID);
    std::string mfg = dev->getManufacturerData();
    bool canon = mfg.size() >= 2 && (uint8_t)mfg[0] == (CANON_COMPANY_ID & 0xff) &&
                 (uint8_t)mfg[1] == (CANON_COMPANY_ID >> 8);
    if (!smart && !canon) continue;
    Serial.printf("  [%u] %s type=%d rssi=%d name='%s' smart=%d\n", (unsigned)scanResults.size(),
                  dev->getAddress().toString().c_str(), dev->getAddress().getType(), dev->getRSSI(),
                  dev->getName().c_str(), smart);
    scanResults.push_back(dev->getAddress());
  }
  Serial.printf("found %u canon device(s)\n", (unsigned)scanResults.size());
}

// List every service/characteristic on the camera, reading values where allowed.
static void dumpGatt() {
  if (!client || !client->isConnected()) {
    Serial.println("not connected");
    return;
  }
  for (NimBLERemoteService *svc : client->getServices(true)) {
    Serial.printf("svc %s\n", svc->getUUID().toString().c_str());
    for (NimBLERemoteCharacteristic *chr : svc->getCharacteristics(true)) {
      Serial.printf("  chr %s h=0x%04x %s%s%s%s%s\n", chr->getUUID().toString().c_str(),
                    chr->getHandle(), chr->canRead() ? "R" : "", chr->canWrite() ? "W" : "",
                    chr->canWriteNoResponse() ? "w" : "", chr->canNotify() ? "N" : "",
                    chr->canIndicate() ? "I" : "");
      if (chr->canRead()) {
        NimBLEAttValue v = chr->readValue();
        Serial.print("    ");
        hexdump("value", v.data(), v.size());
      }
    }
  }
  // Refreshing discovery frees the old characteristic objects; re-resolve.
  NimBLERemoteService *geoSvc = client->getService(GEO_SVC_UUID);
  geoChr = geoSvc ? geoSvc->getCharacteristic(GEO_CHR_UUID) : nullptr;
}

static void printStatus() {
  Serial.printf("name=%s connected=%d geoChr=%d geoEnabled=%d\n", deviceName,
                client && client->isConnected(), geoChr != nullptr, geoEnabled);
  Serial.printf("saved=%s bonds=%d\n", prefs.getString("addr", "(none)").c_str(),
                NimBLEDevice::getNumBonds());
  Serial.printf("gps=%.6f,%.6f alt=%.1f time=%lu auto=%lus\n", lat, lon, alt,
                (unsigned long)nowEpoch(), (unsigned long)autoSendSec);
}

static void handleCommand(String line) {
  line.trim();
  if (line.isEmpty()) return;
  Serial.printf("> %s\n", line.c_str());

  char cmd[16] = {0};
  sscanf(line.c_str(), "%15s", cmd);
  const char *args = line.c_str() + strlen(cmd);

  if (!strcmp(cmd, "scan")) {
    doScan();
  } else if (!strcmp(cmd, "pair")) {
    unsigned n = atoi(args);
    if (n >= scanResults.size()) {
      Serial.println("bad index, run scan first");
      return;
    }
    connectCamera(scanResults[n]);
  } else if (!strcmp(cmd, "connect")) {
    String saved = prefs.getString("addr", "");
    if (saved.isEmpty()) {
      Serial.println("no saved camera");
      return;
    }
    connectCamera(NimBLEAddress(saved.c_str(), prefs.getUChar("type", 0)));
  } else if (!strcmp(cmd, "disconnect")) {
    if (client) client->disconnect();
  } else if (!strcmp(cmd, "forget")) {
    if (client) client->disconnect();
    prefs.remove("addr");
    NimBLEDevice::deleteAllBonds();
    Serial.println("forgotten");
  } else if (!strcmp(cmd, "gps")) {
    double a, b, c = alt;
    int n = sscanf(args, "%lf %lf %lf", &a, &b, &c);
    if (n < 2) {
      Serial.println("usage: gps <lat> <lon> [alt]");
      return;
    }
    lat = a;
    lon = b;
    alt = c;
    printStatus();
  } else if (!strcmp(cmd, "time")) {
    epochBase = strtoul(args, nullptr, 10);
    epochSetAtMs = millis();
    printStatus();
  } else if (!strcmp(cmd, "send")) {
    sendGeo();
  } else if (!strcmp(cmd, "auto")) {
    autoSendSec = atoi(args);
    printStatus();
  } else if (!strcmp(cmd, "shutter")) {
    if (!client || !client->isConnected()) {
      Serial.println("not connected");
      return;
    }
    uint8_t press[2] = {0x00, 0x01}, release[2] = {0x00, 0x02};
    client->setValue(SVC_MODE_UUID, CHR_SHUTTER_UUID, NimBLEAttValue(press, 2));
    delay(200);
    client->setValue(SVC_MODE_UUID, CHR_SHUTTER_UUID, NimBLEAttValue(release, 2));
    Serial.println("shutter fired");
  } else if (!strcmp(cmd, "gatt")) {
    dumpGatt();
  } else if (!strcmp(cmd, "status")) {
    printStatus();
  } else {
    Serial.println("commands: scan, pair <n>, connect, disconnect, forget, gps <lat> <lon> [alt], "
                   "time <epoch>, send, auto <sec>, shutter, gatt, status");
  }
}

void setup() {
  Serial.begin(115200);
  delay(500);

  // Stable per-chip identity, same scheme as furble.
  uint8_t mac[6];
  esp_efuse_mac_get_default(mac);
  uint32_t x = mac[2] << 24 | mac[3] << 16 | mac[4] << 8 | mac[5];
  for (int i = 0; i < 4; i++) {
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    memcpy(&deviceUuid[i * 4], &x, 4);
  }
  snprintf(deviceName, sizeof(deviceName), "camgps-%05lx", (unsigned long)(x & 0xfffff));

  prefs.begin("camgps", false);

  NimBLEDevice::init(deviceName);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);
  NimBLEDevice::setSecurityAuth(true, true, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_YESNO);

  Serial.println("\ncamera-gps proof of concept ready");
  printStatus();
}

void loop() {
  static String line;
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      handleCommand(line);
      line = "";
    } else {
      line += c;
    }
  }

  // Camera asks for location permission; answer from loop, not the BLE callback.
  if (geoRequested && geoChr) {
    geoRequested = false;
    uint8_t enable = 0x01;
    bool ok = geoChr->writeValue(&enable, 1, true);
    Serial.printf("[geo] camera requested location, enabled: %d\n", ok);
  }

  if (autoSendSec && geoEnabled && millis() - lastSendMs > autoSendSec * 1000) {
    lastSendMs = millis();
    sendGeo();
  }
}
