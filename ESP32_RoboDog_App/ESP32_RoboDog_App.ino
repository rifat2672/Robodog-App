/*
  ESP32 Robo Dog — Android App এর জন্য Firmware (WiFi + Bluetooth LE)
  =====================================================================
  ফোনের "Robo Dog" app থেকে WiFi অথবা Bluetooth দিয়ে কন্ট্রোল:
  সামনে / পেছনে / বামে / ডানে, গতি, ধাপের দৈর্ঘ্য, হাঁটার ধরন,
  দাঁড়াও / বসো / শোও / হাই, আর পায়ের ক্যালিব্রেশন।

  Pin:
    GPIO 26 -> Front Left      GPIO 19 -> Front Right
    GPIO 27 -> Back Left       GPIO 18 -> Back Right

  ---------------- Arduino IDE সেটিং (জরুরি) ----------------
    Board          : ESP32 Dev Module
    Partition Scheme: "Huge APP (3MB No OTA/1MB SPIFFS)"
                     WiFi + Bluetooth একসাথে থাকলে code বড় হয়, Default partition এ আঁটে না।
    Library        : ESP32Servo (by Kevin Harrington) — Library Manager থেকে install করো
    BLE library ESP32 core এর সাথেই আসে, আলাদা install লাগবে না।

  ---------------- যোগাযোগ ----------------
    WiFi : TCP port 8888 (কমান্ড), UDP port 8889 (app এর "খোঁজো" বাটন)
           রাউটারের নাম/পাসওয়ার্ড না দিলে বা না পেলে ESP32 নিজেই "RoboDog" হটস্পট চালু করবে
           (পাসওয়ার্ড 12345678, IP 192.168.4.1)
    BLE  : নাম "RoboDog", Nordic UART Service (pairing লাগে না)
    Serial Monitor (115200, "Newline") থেকেও নিচের কমান্ড লিখে টেস্ট করা যায়

  ---------------- কমান্ড (প্রতিটা আলাদা লাইনে) ----------------
    F / B / L / R     সামনে / পেছনে / বামে ঘোরো / ডানে ঘোরো
                      (app বাটন চেপে ধরে রাখলে প্রতি 250ms এ পাঠায়; 1.2s না এলে রোবট থামে)
    S                 থামো
    SPD:1..10         গতি
    STR:10..45        ধাপের দৈর্ঘ্য (ডিগ্রি)
    GAIT:0|1|2        0 = স্থির হাঁটা, 1 = দ্রুত হাঁটা, 2 = পাশ বদল
    ACT:stand|sit|lie|hi
    TRIM:<পা>:<-30..30>   পা: 0=FL 1=FR 2=BL 3=BR, + মানে পা সামনে
    INV:<পা>:<0|1>        servo উল্টো লাগানো থাকলে 1
    CAL:<ডিগ্রি>      সব পা একসাথে ওই angle এ (0 = সোজা দাঁড়)
    RST               ক্যালিব্রেশন রিসেট
    ?                 বর্তমান সেটিং
  উত্তর: ST:speed,stride,gait,trim0,trim1,trim2,trim3,inv0,inv1,inv2,inv3

  পায়ের দিক (ছবি অনুযায়ী):
    বাম পাশের servo: 0° = পা সামনে, 180° = পা পেছনে
    ডান পাশের servo: 0° = পা পেছনে, 180° = পা সামনে
    90° = পা খাড়া নিচে
*/

#define USE_WIFI 1   // 0 করলে WiFi বন্ধ
#define USE_BLE  1   // 0 করলে Bluetooth বন্ধ (তখন Default partition এও আঁটবে)

#include <Preferences.h>
#include <ESP32Servo.h>
#if USE_WIFI
  #include <WiFi.h>
  #include <WiFiUdp.h>
#endif
#if USE_BLE
  #include <BLEDevice.h>
  #include <BLEServer.h>
  #include <BLE2902.h>
#endif

// ================= WiFi সেটিং =================
const char* WIFI_SSID = "YOUR_WIFI_NAME";      // রাউটার না থাকলে এভাবেই রেখে দাও
const char* WIFI_PASS = "YOUR_WIFI_PASSWORD";
const char* AP_SSID   = "RoboDog";             // ESP32 এর নিজের হটস্পট
const char* AP_PASS   = "12345678";            // কমপক্ষে ৮ অক্ষর
const uint16_t TCP_PORT = 8888;
const uint16_t UDP_PORT = 8889;

// ================= BLE সেটিং =================
const char* BLE_NAME = "RoboDog";
#define NUS_SERVICE "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define NUS_RX      "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"   // ফোন -> ESP32
#define NUS_TX      "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"   // ESP32 -> ফোন

// ================= Servo সেটিং =================
enum { LEG_FL = 0, LEG_FR = 1, LEG_BL = 2, LEG_BR = 3 };
const int SERVO_PINS[4] = {26, 19, 27, 18};       // FL, FR, BL, BR

// বাম পাশে servo angle কমালে পা সামনে যায় (-1), ডান পাশে বাড়ালে (+1)
const int8_t BASE_SIGN[4] = {-1, +1, -1, +1};

// SG90 pulse range। 0°/180° তে গোঁ গোঁ করলে MIN বাড়াও, MAX কমাও
const int SERVO_MIN_US = 500;
const int SERVO_MAX_US = 2400;

// ================= হাঁটার সেটিং =================
const float GAIT_OFFSET    = 5.0f;   // হাঁটার সময় পা খাড়া থেকে কত ডিগ্রি হেলে থাকবে
const uint32_t FAILSAFE_MS = 1200;   // ফোন থেকে চলার কমান্ড না এলে এত ms পরে থামবে

// ================= গ্লোবাল =================
Servo servos[4];
Preferences prefs;

struct Config {
  uint8_t speed;     // 1..10
  uint8_t stride;    // 10..45 ডিগ্রি
  uint8_t gait;      // 0 = স্থির (একটা একটা পা), 1 = দ্রুত (কোনাকুনি জোড়া), 2 = পাশ বদল
  int8_t  trim[4];   // প্রতিটা পায়ের ক্যালিব্রেশন, + মানে পা সামনে
  bool    inv[4];    // servo উল্টো লাগানো থাকলে true
} cfg = {5, 30, 2, {0, 0, 0, 0}, {false, false, false, false}};

enum MoveDir : uint8_t { D_STOP, D_FWD, D_BACK, D_LEFT, D_RIGHT };
enum RunMode : uint8_t { M_IDLE, M_WALK, M_ACTION };

RunMode  mode   = M_IDLE;
MoveDir  curDir = D_STOP;
uint8_t  walkStep = 0;
uint32_t lastMoveCmd = 0;
uint32_t lastTick = 0;
uint32_t saveAt = 0;                 // সেটিং সেভ করার সময় (0 = কিছু সেভ বাকি নেই)

// পায়ের বর্তমান অবস্থা আর চলমান motion frame
float legNow[4]  = {0, 0, 0, 0};
float legFrom[4] = {0, 0, 0, 0};
float legTo[4]   = {0, 0, 0, 0};
uint32_t frameStart = 0, frameDur = 1;
bool frameRunning = false;

// লাইন ধরে কমান্ড পড়ার বাফার
struct LineBuf { char b[64]; uint8_t n; };

void handleCommand(char* c);   // নিচে আছে

// ================= Pose / Action =================
// a[] = FL, FR, BL, BR এর leg angle (0 = খাড়া, + = সামনে)
// mask = কোন পা নড়বে (bit0=FL, bit1=FR, bit2=BL, bit3=BR)
struct KeyFrame { int8_t a[4]; uint8_t mask; uint16_t ms; };

const KeyFrame ACT_STAND[] = {
  {{0, 0, 0, 0}, 0x0F, 500},
};
const KeyFrame ACT_SIT[] = {
  {{0, 0, 0, 0},     0x0F, 300},
  {{0, 0, -75, -75}, 0x0C, 700},     // পেছনের দুই পা ভাঁজ
};
const KeyFrame ACT_LIE[] = {
  {{75, 75, -75, -75}, 0x0F, 800},   // সামনের পা সামনে, পেছনের পা পেছনে
};
const KeyFrame ACT_HI[] = {
  {{0, 0, 0, 0},     0x0F, 300},
  {{0, 0, -75, -75}, 0x0C, 600},     // বসো
  {{0, 80, 0, 0},    0x02, 400},     // Front Right সামনে তোলো
  {{0, 50, 0, 0},    0x02, 250},
  {{0, 80, 0, 0},    0x02, 250},
  {{0, 50, 0, 0},    0x02, 250},
  {{0, 80, 0, 0},    0x02, 250},
  {{0, 0, 0, 0},     0x02, 400},     // নামাও, বসেই থাকবে
};

const KeyFrame* actSeq = nullptr;
uint8_t actLen = 0, actIdx = 0;

// ================= Config সেভ / লোড =================
void loadConfig() {
  prefs.begin("robodog", true);
  cfg.speed  = constrain(prefs.getUChar("speed", 5), 1, 10);
  cfg.stride = constrain(prefs.getUChar("stride", 30), 10, 45);
  cfg.gait   = constrain(prefs.getUChar("gait", 2), 0, 2);
  for (int i = 0; i < 4; i++) {
    char k[4];
    snprintf(k, sizeof(k), "t%d", i);
    cfg.trim[i] = constrain(prefs.getChar(k, 0), -30, 30);
    snprintf(k, sizeof(k), "i%d", i);
    cfg.inv[i] = prefs.getBool(k, false);
  }
  prefs.end();
}

void saveConfig() {
  prefs.begin("robodog", false);
  prefs.putUChar("speed", cfg.speed);
  prefs.putUChar("stride", cfg.stride);
  prefs.putUChar("gait", cfg.gait);
  for (int i = 0; i < 4; i++) {
    char k[4];
    snprintf(k, sizeof(k), "t%d", i);
    prefs.putChar(k, cfg.trim[i]);
    snprintf(k, sizeof(k), "i%d", i);
    prefs.putBool(k, cfg.inv[i]);
  }
  prefs.end();
}

// বারবার বাটন চাপলে প্রতিবার flash এ না লিখে একটু পরে একবারে সেভ
void markDirty() {
  saveAt = millis() + 800;
  if (saveAt == 0) saveAt = 1;
}

// ================= Servo / Motion =================
// leg angle (0 = খাড়া, + = সামনে) কে servo pulse এ রূপান্তর
void writeLeg(int i, float L) {
  legNow[i] = L;
  int sign = BASE_SIGN[i] * (cfg.inv[i] ? -1 : 1);
  float a = 90.0f + sign * (L + cfg.trim[i]);
  a = constrain(a, 0.0f, 180.0f);
  int us = SERVO_MIN_US + (int)(a * (SERVO_MAX_US - SERVO_MIN_US) / 180.0f + 0.5f);
  servos[i].writeMicroseconds(us);
}

// বর্তমান অবস্থান থেকে target এ ms সময়ের মধ্যে মসৃণভাবে যাবে
void startFrame(const float tgt[4], uint8_t mask, uint16_t ms) {
  for (int i = 0; i < 4; i++) {
    legFrom[i] = legNow[i];
    legTo[i]   = (mask & (1 << i)) ? tgt[i] : legNow[i];
  }
  frameStart = millis();
  frameDur = ms < 1 ? 1 : ms;
  frameRunning = true;
}

void updateFrame() {
  if (!frameRunning) return;
  uint32_t t = millis() - frameStart;
  float p = (t >= frameDur) ? 1.0f : (float)t / frameDur;
  float e = 0.5f - 0.5f * cosf(p * PI);          // ease in-out
  for (int i = 0; i < 4; i++) {
    writeLeg(i, legFrom[i] + (legTo[i] - legFrom[i]) * e);
  }
  if (p >= 1.0f) frameRunning = false;
}

uint16_t swingMs() { return 420 - (cfg.speed - 1) * 35; }   // গতি 1 -> 420ms, 10 -> 105ms
uint16_t powerMs() { return swingMs() * 3 / 2; }

// প্রতিটা পা কোন দিকে ঠেলবে: +1 = শরীর সামনে নেবে, -1 = পেছনে
void dirSigns(MoveDir d, int8_t s[4]) {
  int8_t left = 1, right = 1;
  if (d == D_BACK)  { left = -1; right = -1; }
  if (d == D_LEFT)  { left = -1; right =  1; }   // বামে ঘোরা: ডান পাশ সামনে, বাম পাশ পেছনে
  if (d == D_RIGHT) { left =  1; right = -1; }
  s[LEG_FL] = left;  s[LEG_BL] = left;
  s[LEG_FR] = right; s[LEG_BR] = right;
}

void nextWalkFrame() {
  int8_t s[4];
  dirSigns(curDir, s);
  float lo[4], hi[4];
  for (int i = 0; i < 4; i++) {
    lo[i] = s[i] * GAIT_OFFSET;                    // ঠেলা শেষে পা প্রায় খাড়া
    hi[i] = s[i] * (GAIT_OFFSET + cfg.stride);     // পা এগিয়ে রাখা অবস্থা
  }

  if (cfg.gait == 0) {
    // স্থির হাঁটা: একটা একটা পা এগোয়, তারপর সব একসাথে ঠেলে
    static const uint8_t ORDER[4] = {LEG_FL, LEG_BR, LEG_FR, LEG_BL};
    if (walkStep < 4) startFrame(hi, 1 << ORDER[walkStep], swingMs());
    else              startFrame(lo, 0x0F, powerMs());
    walkStep = (walkStep + 1) % 5;
  } else if (cfg.gait == 1) {
    // দ্রুত হাঁটা: কোনাকুনি দুই পা একসাথে এগোয়, তারপর সব একসাথে ঠেলে
    if      (walkStep == 0) startFrame(hi, (1 << LEG_FL) | (1 << LEG_BR), swingMs());
    else if (walkStep == 1) startFrame(hi, (1 << LEG_FR) | (1 << LEG_BL), swingMs());
    else                    startFrame(lo, 0x0F, powerMs());
    walkStep = (walkStep + 1) % 3;
  } else {
    // পাশ বদল (সামনে যাওয়ার সময়):
    //   ১. বামের দুই পা সামনে   ২. ডানের দুই পা পেছনে
    //   ৩. ডানের দুই পা সামনে   ৪. বামের দুই পা পেছনে   -> আবার ১
    // পেছনে যাওয়া বা ঘোরার সময় dirSigns() দিক উল্টে দেয়।
    // অন্য ক্রম চাইলে শুধু এই টেবিলটা বদলাও (mask: কোন পা, dir: +1 সামনে / -1 পেছনে)
    const uint8_t LEFT  = (1 << LEG_FL) | (1 << LEG_BL);
    const uint8_t RIGHT = (1 << LEG_FR) | (1 << LEG_BR);
    struct SideStep { uint8_t mask; int8_t dir; };
    const SideStep SIDE_STEPS[4] = {
      {LEFT, +1}, {RIGHT, -1}, {RIGHT, +1}, {LEFT, -1}
    };
    const SideStep& st = SIDE_STEPS[walkStep % 4];
    float A = cfg.stride / 2.0f;                   // পা খাড়া থেকে ±A ডিগ্রি দোলে (মোট = ধাপের দৈর্ঘ্য)
    float t[4];
    for (int i = 0; i < 4; i++) t[i] = s[i] * st.dir * A;
    startFrame(t, st.mask, powerMs());
    walkStep = (walkStep + 1) % 4;
  }
}

void stopWalk() {
  mode = M_IDLE;
  curDir = D_STOP;
  const float z[4] = {0, 0, 0, 0};
  startFrame(z, 0x0F, 300);                        // দাঁড়িয়ে যাবে
}

void moveCmd(MoveDir nd) {
  if (nd == D_STOP) {
    if (mode == M_WALK) stopWalk();
    return;
  }
  if (mode != M_WALK) {
    frameRunning = false;           // আগের motion বাদ দিয়ে এখনই হাঁটা শুরু
    mode = M_WALK;
    curDir = nd;
    walkStep = 0;
  } else if (nd != curDir) {
    curDir = nd;                    // চলমান ধাপ শেষ হলে নতুন দিকে
    walkStep = 0;
  }
  lastMoveCmd = millis();
}

void nextActionFrame() {
  if (actIdx >= actLen) { mode = M_IDLE; return; } // শেষ pose এ থেকে যাবে
  const KeyFrame& k = actSeq[actIdx++];
  float t[4];
  for (int i = 0; i < 4; i++) t[i] = k.a[i];
  startFrame(t, k.mask, k.ms);
}

void playAction(const KeyFrame* seq, uint8_t len) {
  actSeq = seq; actLen = len; actIdx = 0;
  mode = M_ACTION; curDir = D_STOP;
  frameRunning = false;
  nextActionFrame();
}
#define PLAY(seq) playAction(seq, sizeof(seq) / sizeof(seq[0]))

void calPose(int p) {
  p = constrain(p, -45, 45);
  mode = M_IDLE;
  curDir = D_STOP;
  const float t[4] = {(float)p, (float)p, (float)p, (float)p};
  startFrame(t, 0x0F, 400);
}

// trim বদলালে সাথে সাথে পায়ে দেখাও
void refreshPose() {
  if (!frameRunning) {
    for (int i = 0; i < 4; i++) writeLeg(i, legNow[i]);
  }
}

// ফোনের সংযোগ কেটে গেলে হাঁটা বন্ধ
void onLinkLost() {
  if (mode == M_WALK) stopWalk();
}

// ================= WiFi =================
#if USE_WIFI
WiFiServer tcpServer(TCP_PORT);
WiFiClient tcpClient;
WiFiUDP    udp;
IPAddress  myIP;
LineBuf    tcpLine = {{0}, 0};
bool       tcpActive = false;

void startWiFi() {
  bool haveRouter = strcmp(WIFI_SSID, "YOUR_WIFI_NAME") != 0 && strlen(WIFI_SSID) > 0;

  if (haveRouter) {
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASS);
    Serial.print("[WiFi] Connecting to router");
    uint32_t t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < 15000) {
      delay(500);
      Serial.print(".");
    }
    Serial.println();
  }

  if (haveRouter && WiFi.status() == WL_CONNECTED) {
    myIP = WiFi.localIP();
    Serial.print("[WiFi] Connected to router. IP: ");
    Serial.println(myIP);
  } else {
    WiFi.disconnect(true);
    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASS);
    myIP = WiFi.softAPIP();
    Serial.printf("[WiFi] Hotspot \"%s\" (password %s) চালু। IP: ", AP_SSID, AP_PASS);
    Serial.println(myIP);
  }

  tcpServer.begin();
  tcpServer.setNoDelay(true);
  udp.begin(UDP_PORT);
}

void pollTcp() {
  // নতুন ফোন সংযোগ করলে পুরনোটা বাদ
  if (tcpServer.hasClient()) {
    WiFiClient nc = tcpServer.accept();
    if (tcpActive) tcpClient.stop();
    tcpClient = nc;
    tcpClient.setNoDelay(true);
    tcpLine.n = 0;
    tcpActive = true;
    Serial.print("[WiFi] App connected: ");
    Serial.println(tcpClient.remoteIP());
  }

  if (!tcpActive) return;

  if (!tcpClient.connected()) {
    tcpActive = false;
    tcpClient.stop();
    Serial.println("[WiFi] App disconnected");
    onLinkLost();
    return;
  }

  while (tcpClient.available()) {
    int ch = tcpClient.read();
    if (ch < 0) break;
    char c = (char)ch;
    if (c == '\n' || c == '\r') {
      if (tcpLine.n) {
        tcpLine.b[tcpLine.n] = 0;
        tcpLine.n = 0;
        handleCommand(tcpLine.b);
      }
    } else if (tcpLine.n < sizeof(tcpLine.b) - 1) {
      tcpLine.b[tcpLine.n++] = c;
    }
  }
}

// app এর "খোঁজো" বাটন UDP broadcast পাঠায়, এখানে IP দিয়ে উত্তর দিই
void pollUdp() {
  int size = udp.parsePacket();
  if (size <= 0) return;
  char b[32];
  int len = udp.read(b, sizeof(b) - 1);
  if (len < 0) len = 0;
  b[len] = 0;
  if (strncmp(b, "ROBODOG?", 8) == 0) {
    udp.beginPacket(udp.remoteIP(), udp.remotePort());
    udp.print("ROBODOG:");
    udp.print(myIP.toString());
    udp.endPacket();
  }
}
#endif

// ================= Bluetooth LE =================
#if USE_BLE
BLECharacteristic* bleTx = nullptr;
volatile bool bleConnected = false;
volatile bool bleJustConnected = false;
volatile bool bleJustDisconnected = false;
uint32_t bleAdvAt = 0;

// BLE callback আলাদা task এ চলে, তাই কমান্ড queue তে রেখে loop() থেকে চালাই
struct BleMsg { char s[48]; };
QueueHandle_t bleQueue;

class DogServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override {
    bleConnected = true;
    bleJustConnected = true;
  }
  void onDisconnect(BLEServer* s) override {
    bleConnected = false;
    bleJustDisconnected = true;
  }
};

class DogRxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    auto v = c->getValue();
    BleMsg m;
    size_t n = v.length();
    if (n >= sizeof(m.s)) n = sizeof(m.s) - 1;
    memcpy(m.s, v.c_str(), n);
    m.s[n] = 0;
    xQueueSend(bleQueue, &m, 0);
  }
};

void startBLE() {
  bleQueue = xQueueCreate(16, sizeof(BleMsg));

  BLEDevice::init(BLE_NAME);
  BLEDevice::setMTU(185);
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new DogServerCallbacks());

  BLEService* svc = server->createService(NUS_SERVICE);
  bleTx = svc->createCharacteristic(NUS_TX, BLECharacteristic::PROPERTY_NOTIFY);
  bleTx->addDescriptor(new BLE2902());
  BLECharacteristic* rx = svc->createCharacteristic(
      NUS_RX, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx->setCallbacks(new DogRxCallbacks());
  svc->start();

  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(NUS_SERVICE);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.printf("[BLE] \"%s\" নামে Bluetooth চালু\n", BLE_NAME);
}

void pollBLE() {
  BleMsg m;
  while (xQueueReceive(bleQueue, &m, 0) == pdTRUE) {
    // একটা write এ একাধিক লাইন থাকতে পারে
    char* save = nullptr;
    for (char* tok = strtok_r(m.s, "\r\n", &save); tok; tok = strtok_r(nullptr, "\r\n", &save)) {
      handleCommand(tok);
    }
  }
  if (bleJustConnected) {
    bleJustConnected = false;
    Serial.println("[BLE] App connected");
  }
  if (bleJustDisconnected) {
    bleJustDisconnected = false;
    Serial.println("[BLE] App disconnected");
    onLinkLost();
    bleAdvAt = millis() + 300;          // একটু পরে আবার advertise, যাতে ফোন আবার খুঁজে পায়
    if (bleAdvAt == 0) bleAdvAt = 1;
  }
  if (bleAdvAt && (int32_t)(millis() - bleAdvAt) >= 0) {
    bleAdvAt = 0;
    BLEDevice::startAdvertising();
  }
}
#endif

// ================= উত্তর পাঠানো =================
void reply(const char* line) {
  Serial.println(line);
#if USE_WIFI
  if (tcpActive && tcpClient.connected()) {
    tcpClient.write((const uint8_t*)line, strlen(line));
    tcpClient.write((const uint8_t*)"\n", 1);
  }
#endif
#if USE_BLE
  if (bleConnected && bleTx) {
    char buf[96];
    int n = snprintf(buf, sizeof(buf), "%s\n", line);
    if (n > (int)sizeof(buf) - 1) n = sizeof(buf) - 1;
    bleTx->setValue((uint8_t*)buf, n);
    bleTx->notify();
  }
#endif
}

void sendStatus() {
  char buf[80];
  snprintf(buf, sizeof(buf), "ST:%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
           cfg.speed, cfg.stride, cfg.gait,
           cfg.trim[0], cfg.trim[1], cfg.trim[2], cfg.trim[3],
           cfg.inv[0], cfg.inv[1], cfg.inv[2], cfg.inv[3]);
  reply(buf);
}

// ================= কমান্ড =================
// "TRIM:2:-5" এর মতো আর্গুমেন্ট থেকে পা আর মান বের করে
bool parseLegArg(char* arg, int& leg, int& val) {
  if (!arg) return false;
  char* v = strchr(arg, ':');
  if (!v) return false;
  leg = atoi(arg);
  val = atoi(v + 1);
  return leg >= 0 && leg <= 3;
}

void handleCommand(char* c) {
  // সামনে-পেছনের ফাঁকা জায়গা বাদ
  while (*c == ' ' || *c == '\t') c++;
  size_t n = strlen(c);
  while (n && (c[n - 1] == ' ' || c[n - 1] == '\t' || c[n - 1] == '\r')) c[--n] = 0;
  if (!n) return;

  // ':' এর আগের অংশ বড় হাতের অক্ষরে (Serial Monitor এ ছোট হাতে লিখলেও চলবে)
  for (char* p = c; *p && *p != ':'; p++) *p = toupper((unsigned char)*p);
  char* arg = strchr(c, ':');
  if (arg) *arg++ = 0;

  int leg, val;

  if      (!strcmp(c, "F")) moveCmd(D_FWD);
  else if (!strcmp(c, "B")) moveCmd(D_BACK);
  else if (!strcmp(c, "L")) moveCmd(D_LEFT);
  else if (!strcmp(c, "R")) moveCmd(D_RIGHT);
  else if (!strcmp(c, "S")) moveCmd(D_STOP);
  else if (!strcmp(c, "SPD") && arg) {
    cfg.speed = constrain(atoi(arg), 1, 10);
    markDirty(); sendStatus();
  }
  else if (!strcmp(c, "STR") && arg) {
    cfg.stride = constrain(atoi(arg), 10, 45);
    markDirty(); sendStatus();
  }
  else if (!strcmp(c, "GAIT") && arg) {
    cfg.gait = constrain(atoi(arg), 0, 2);
    walkStep = 0;
    markDirty(); sendStatus();
  }
  else if (!strcmp(c, "ACT") && arg) {
    if      (!strcasecmp(arg, "stand")) PLAY(ACT_STAND);
    else if (!strcasecmp(arg, "sit"))   PLAY(ACT_SIT);
    else if (!strcasecmp(arg, "lie"))   PLAY(ACT_LIE);
    else if (!strcasecmp(arg, "hi"))    PLAY(ACT_HI);
    else reply("ERR:unknown action");
  }
  else if (!strcmp(c, "TRIM") && parseLegArg(arg, leg, val)) {
    cfg.trim[leg] = constrain(val, -30, 30);
    markDirty(); refreshPose(); sendStatus();
  }
  else if (!strcmp(c, "INV") && parseLegArg(arg, leg, val)) {
    cfg.inv[leg] = val != 0;
    markDirty(); refreshPose(); sendStatus();
  }
  else if (!strcmp(c, "CAL") && arg) {
    calPose(atoi(arg));
  }
  else if (!strcmp(c, "RST")) {
    for (int i = 0; i < 4; i++) { cfg.trim[i] = 0; cfg.inv[i] = false; }
    markDirty(); refreshPose(); sendStatus();
  }
  else if (!strcmp(c, "?")) {
    sendStatus();
  }
  else {
    reply("ERR:unknown command");
  }
}

// Serial Monitor থেকে টেস্ট করার জন্য
LineBuf serialLine = {{0}, 0};
void pollSerial() {
  while (Serial.available()) {
    char ch = (char)Serial.read();
    if (ch == '\n' || ch == '\r') {
      if (serialLine.n) {
        serialLine.b[serialLine.n] = 0;
        serialLine.n = 0;
        handleCommand(serialLine.b);
      }
    } else if (serialLine.n < sizeof(serialLine.b) - 1) {
      serialLine.b[serialLine.n++] = ch;
    }
  }
}

// ================= Setup / Loop =================
void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println("\n=== Robo Dog ===");

  loadConfig();

  ESP32PWM::allocateTimer(0);
  ESP32PWM::allocateTimer(1);
  ESP32PWM::allocateTimer(2);
  ESP32PWM::allocateTimer(3);

  for (int i = 0; i < 4; i++) {
    servos[i].setPeriodHertz(50);
    servos[i].attach(SERVO_PINS[i], SERVO_MIN_US, SERVO_MAX_US);
    writeLeg(i, 0);                     // দাঁড়ানো অবস্থা
    delay(200);                         // একসাথে ৪টা চালু হলে current spike, তাই একটা একটা করে
  }

#if USE_WIFI
  startWiFi();
#endif
#if USE_BLE
  startBLE();
#endif

  Serial.println("Robo Dog ready. Serial এ '?' লিখে টেস্ট করতে পারো।");
}

void loop() {
  pollSerial();
#if USE_WIFI
  pollTcp();
  pollUdp();
#endif
#if USE_BLE
  pollBLE();
#endif

  uint32_t now = millis();
  if (now - lastTick >= 10) {           // প্রতি 10ms এ motion আপডেট
    lastTick = now;
    updateFrame();

    if (!frameRunning) {
      if (mode == M_WALK)        nextWalkFrame();
      else if (mode == M_ACTION) nextActionFrame();
    }

    // ফোন থেকে চলার কমান্ড বন্ধ হলে নিজে থেমে যাবে
    if (mode == M_WALK && now - lastMoveCmd > FAILSAFE_MS) stopWalk();
  }

  if (saveAt && (int32_t)(now - saveAt) >= 0) {
    saveAt = 0;
    saveConfig();
  }

  delay(1);
}
