/*
  probe.ino — เฟิร์มแวร์ตรวจสอบตู้เก็บเงิน (MEGA 2560 PRO)
  ใช้หาว่าขาไหนของ MEGA ต่อกับอะไรบนบอร์ดฐานของตู้ Tiger  ไม่รับเงิน ไม่สั่งอะไรเองเด็ดขาด

  ทำอะไร
   - ทุกขา (ยกเว้น D0/D1 ที่ต่อ USB) ตั้งเป็น INPUT (ไม่จ่ายไฟออก)
     รวมขาส่งของพอร์ต serial (D14/D16/D18) ด้วย: เปิดเฉพาะตัวรับ ตัวส่งปิดไว้ จะเปิดชั่วคราวตอนสั่ง SEND เท่านั้น
   - ขาไหนเปลี่ยน 0/1 พิมพ์บอก พร้อมนับพัลส์ (เครื่องรับเหรียญมักส่งเป็นพัลส์)
   - ฟัง Serial1 (D18/D19), Serial2 (D16/D17), Serial3 (D14/D15) พร้อมกัน พิมพ์ไบต์ที่เข้ามาเป็นเลขฐาน 16
     ค่าเริ่ม 9600 8E1 (แบบเครื่องรับแบงก์ ICT) เปลี่ยนได้ด้วยคำสั่ง BAUD
   - ไม่ตอบรับแบงก์ -> แบงก์ที่สอดจะถูกคายคืน (ไม่มีเงินเข้าตู้ระหว่างทดสอบ)

  คำสั่ง (พิมพ์แล้วกด Enter ใน หน้าต่างตรวจสอบ)
   HELP                    วิธีใช้
   SIG                     อ่านรหัสชิป (ต้องได้ 1E 98 01)
   STATE                   พิมพ์สถานะทุกขาตอนนี้
   ANALOG                  ค่าแอนะล็อก A0-A15
   PULLUP ON|OFF           เปิด/ปิดตัวต้านทานดึงขึ้นในชิป (ช่วยอ่านสวิตช์ประตูที่ต่อลงกราวด์)
   BAUD <1|2|3> <baud> <8N1|8E1>   ตั้งความเร็วพอร์ต serial
   ARM                     ปลดล็อกคำสั่ง SEND และ PULSE 60 วินาที (ใช้ตอนเจ้าของอยู่หน้าตู้และเอาเงินออกแล้วเท่านั้น)
   SEND <1|2|3> <hex hex ...>      ส่งไบต์ออกพอร์ต (ต้อง ARM ก่อน — ไบต์ 02 ทำให้เครื่อง ICT กลืนแบงก์ลงตู้ได้)
   PULSE <ขา> <ms> [HIGH|LOW]      จ่ายไฟออกขานั้นชั่วคราว สูงสุด 2000 ms แล้วกลับเป็น INPUT
*/
#include <avr/boot.h>

#define FW "probe-1.1"
#define NPIN 70                // D0..D53 + A0..A15 (A0 = 54)
#define ARM_MS 60000UL
#define DEBOUNCE_MS 2
#define BURST_MS 250           // พัลส์ห่างกันไม่เกินนี้นับเป็นชุดเดียวกัน

static uint8_t lastVal[NPIN];
static unsigned long lastChange[NPIN];
static uint16_t burst[NPIN];
static unsigned long burstAt[NPIN];
static bool watch[NPIN];
static bool pullup = false;
static unsigned long armedUntil = 0;
static char line[96];
static uint8_t lineLen = 0;
static HardwareSerial* PORTS[4] = { 0, &Serial1, &Serial2, &Serial3 };
static unsigned long portLast[4];
static uint8_t portCount[4];
static uint8_t ictStage[4];

// เปิด/ปิดตัวส่งของ Serial1-3  ปิด = ขา TX (D18/D16/D14) กลับเป็นขาธรรมดา (INPUT ไม่จ่ายไฟ)
static void txEnable(uint8_t port, bool on) {
  volatile uint8_t* reg = port == 1 ? &UCSR1B : port == 2 ? &UCSR2B : &UCSR3B;
  uint8_t bit = port == 1 ? _BV(TXEN1) : port == 2 ? _BV(TXEN2) : _BV(TXEN3);
  uint8_t sreg = SREG; cli();
  if (on) *reg |= bit; else *reg &= ~bit;
  SREG = sreg;
}
static bool armed() { return armedUntil != 0 && millis() < armedUntil; }

static void pinName(uint8_t p) {
  if (p >= 54) { Serial.print('A'); Serial.print(p - 54); Serial.print("(D"); Serial.print(p); Serial.print(')'); }
  else { Serial.print('D'); Serial.print(p); }
}
static void stamp() { Serial.print('['); Serial.print(millis() / 1000.0, 1); Serial.print("s] "); }

static void setupPins() {
  for (uint8_t p = 0; p < NPIN; p++) {
    // D0/D1 = USB กับคอม, D15/D17/D19 = ขารับของพอร์ต serial 3 ชุด (ดูไบต์แทน)
    // D14/D16/D18 (ขาส่ง) ตัวส่งปิดอยู่ จึงเฝ้าดูเหมือนขาธรรมดาได้
    watch[p] = !(p <= 1 || p == 15 || p == 17 || p == 19);
    if (!watch[p]) continue;
    pinMode(p, pullup ? INPUT_PULLUP : INPUT);
  }
  delay(5);
  for (uint8_t p = 0; p < NPIN; p++) if (watch[p]) { lastVal[p] = digitalRead(p); lastChange[p] = millis(); burst[p] = 0; }
}

static void printState() {
  Serial.println(F("--- สถานะทุกขา (1 = มีไฟ, 0 = ไม่มีไฟ) ---"));
  uint8_t col = 0;
  for (uint8_t p = 0; p < NPIN; p++) {
    if (!watch[p]) continue;
    pinName(p); Serial.print('='); Serial.print(digitalRead(p)); Serial.print(F("  "));
    if (++col % 10 == 0) Serial.println();
  }
  Serial.println();
}

static void help() {
  Serial.println(F("คำสั่ง: HELP  SIG  STATE  ANALOG  PULLUP ON|OFF  BAUD <1-3> <baud> <8N1|8E1>  ARM  SEND <1-3> <hex..>  PULSE <ขา> <ms> [HIGH|LOW]"));
  Serial.println(F("SEND และ PULSE ต้องพิมพ์ ARM ก่อน (รอบแรกห้ามใช้)"));
  Serial.println(F("ขาเขียนแบบ 22 หรือ D22 หรือ A3"));
}

static int parsePin(const char* s) {
  if (!s) return -1;
  if (s[0] == 'A' || s[0] == 'a') { int a = atoi(s + 1); return (a >= 0 && a <= 15) ? 54 + a : -1; }
  if (s[0] == 'D' || s[0] == 'd') s++;
  if (s[0] < '0' || s[0] > '9') return -1;
  int p = atoi(s); return (p >= 0 && p < NPIN) ? p : -1;
}

static void doPulse(int p, long ms, bool high) {
  if (!armed()) { Serial.println(F("ยังไม่ได้ ARM (พิมพ์ ARM ก่อน ใช้ได้ 60 วินาที)")); return; }
  if (p < 0 || !watch[p]) { Serial.println(F("ขานี้สั่งไม่ได้")); return; }
  if (ms < 10) ms = 10; if (ms > 2000) ms = 2000;
  stamp(); Serial.print(F("PULSE ")); pinName(p); Serial.print(high ? F(" HIGH ") : F(" LOW ")); Serial.print(ms); Serial.println(F(" ms"));
  digitalWrite(p, high ? HIGH : LOW);
  pinMode(p, OUTPUT);
  unsigned long t0 = millis();
  while (millis() - t0 < (unsigned long)ms) { }
  pinMode(p, pullup ? INPUT_PULLUP : INPUT);
  stamp(); Serial.println(F("PULSE END (ขากลับเป็น INPUT แล้ว)"));
}

static void setBaud(int port, long baud, const char* fmt) {
  if (port < 1 || port > 3 || baud < 300 || baud > 115200) { Serial.println(F("BAUD ผิดรูปแบบ")); return; }
  uint8_t cfg = (fmt && (fmt[1] == 'E' || fmt[1] == 'e')) ? SERIAL_8E1 : SERIAL_8N1;
  PORTS[port]->end(); PORTS[port]->begin(baud, cfg); txEnable(port, false);
  Serial.print(F("Serial")); Serial.print(port); Serial.print(F(" = ")); Serial.print(baud); Serial.println(cfg == SERIAL_8E1 ? F(" 8E1") : F(" 8N1"));
}

static void command(char* s) {
  for (char* c = s; *c; c++) if (*c >= 'a' && *c <= 'z') *c -= 32;
  char* cmd = strtok(s, " ");
  if (!cmd) return;
  if (!strcmp(cmd, "HELP")) help();
  else if (!strcmp(cmd, "SIG")) {
    Serial.print(F("SIG "));
    uint8_t a = boot_signature_byte_get(0), b = boot_signature_byte_get(2), c = boot_signature_byte_get(4);
    Serial.print(a, HEX); Serial.print(' '); Serial.print(b, HEX); Serial.print(' '); Serial.print(c, HEX);
    Serial.println((a == 0x1E && b == 0x98 && c == 0x01) ? F("  = ATmega2560 ถูกต้อง") : F("  = ไม่ใช่ ATmega2560 !"));
  }
  else if (!strcmp(cmd, "STATE")) printState();
  else if (!strcmp(cmd, "ANALOG")) {
    for (uint8_t a = 0; a < 16; a++) { Serial.print('A'); Serial.print(a); Serial.print('='); Serial.print(analogRead(54 + a)); Serial.print(F("  ")); if (a == 7) Serial.println(); }
    Serial.println();
    setupPins();   // analogRead ไม่เปลี่ยนโหมดขา แต่อ่านค่าเริ่มใหม่กันรายงานเปลี่ยนหลอก
  }
  else if (!strcmp(cmd, "PULLUP")) { char* v = strtok(0, " "); pullup = v && !strcmp(v, "ON"); setupPins(); Serial.println(pullup ? F("PULLUP ON") : F("PULLUP OFF")); printState(); }
  else if (!strcmp(cmd, "BAUD")) { char* a = strtok(0, " "); char* b = strtok(0, " "); char* f = strtok(0, " "); setBaud(a ? atoi(a) : 0, b ? atol(b) : 0, f ? f : "8N1"); }
  else if (!strcmp(cmd, "SEND")) {
    char* a = strtok(0, " "); int port = a ? atoi(a) : 0;
    if (port < 1 || port > 3) { Serial.println(F("SEND ผิดรูปแบบ")); return; }
    if (!armed()) { Serial.println(F("ยังไม่ได้ ARM (พิมพ์ ARM ก่อน ใช้ได้ 60 วินาที) — SEND ทำให้เครื่องรับแบงก์กลืนแบงก์ได้")); return; }
    txEnable(port, true); delay(3);            // ให้สายนิ่งที่ระดับพักก่อนส่ง
    Serial.print(F("SEND Serial")); Serial.print(port); Serial.print(':');
    for (char* x = strtok(0, " "); x; x = strtok(0, " ")) { uint8_t v = (uint8_t)strtol(x, 0, 16); PORTS[port]->write(v); Serial.print(' '); if (v < 16) Serial.print('0'); Serial.print(v, HEX); }
    Serial.println();
    PORTS[port]->flush(); delay(3);
    txEnable(port, false);                     // ส่งเสร็จ ขาส่งกลับเป็น INPUT
    setupPins();                               // อ่านค่าเริ่มใหม่ กันรายงานเปลี่ยนหลอกที่ขาส่ง
  }
  else if (!strcmp(cmd, "ARM")) { armedUntil = millis() + ARM_MS; Serial.println(F("ARM: ใช้ SEND/PULSE ได้ 60 วินาที — ตรวจว่าเอาเงินออกจากตู้แล้ว")); }
  else if (!strcmp(cmd, "PULSE")) { char* a = strtok(0, " "); char* b = strtok(0, " "); char* c = strtok(0, " "); doPulse(parsePin(a), b ? atol(b) : 0, !(c && !strcmp(c, "LOW"))); }
  else { Serial.print(F("ไม่รู้จักคำสั่ง: ")); Serial.println(cmd); }
}

static void pollPins() {
  unsigned long now = millis();
  for (uint8_t p = 0; p < NPIN; p++) {
    if (!watch[p]) continue;
    uint8_t v = digitalRead(p);
    if (v != lastVal[p] && now - lastChange[p] >= DEBOUNCE_MS) {
      lastVal[p] = v; lastChange[p] = now;
      if (burst[p] == 0) { stamp(); Serial.print(F("PIN ")); pinName(p); Serial.println(v ? F(" 0->1") : F(" 1->0")); }
      burst[p]++; burstAt[p] = now;
    }
    if (burst[p] && now - burstAt[p] > BURST_MS) {        // เงียบแล้ว = จบชุด
      if (burst[p] > 1) { stamp(); pinName(p); Serial.print(F(" เปลี่ยน ")); Serial.print(burst[p]); Serial.print(F(" ครั้ง (~")); Serial.print(burst[p] / 2); Serial.print(F(" พัลส์) จบที่ค่า ")); Serial.println(lastVal[p]); }
      burst[p] = 0;
    }
  }
}

static void pollPorts() {
  unsigned long now = millis();
  for (uint8_t i = 1; i <= 3; i++) {
    while (PORTS[i]->available()) {
      uint8_t b = PORTS[i]->read();
      if (portCount[i] == 0) { stamp(); Serial.print(F("Serial")); Serial.print(i); Serial.print(F(" <-")); }
      Serial.print(' '); if (b < 16) Serial.print('0'); Serial.print(b, HEX);
      portCount[i]++; portLast[i] = now;
      // เครื่องรับแบงก์ ICT เปิดเครื่องแล้วส่ง 80 8F รอให้ตอบ 02
      if (b == 0x80) ictStage[i] = 1; else if (b == 0x8F && ictStage[i] == 1) { ictStage[i] = 2; } else ictStage[i] = 0;
      if (b == 0x81) ictStage[i] = 3;
    }
    if (portCount[i] && (now - portLast[i] > 40 || portCount[i] >= 24)) {
      Serial.println();
      if (ictStage[i] == 2) { Serial.print(F("   ^ น่าจะเป็นเครื่องรับแบงก์ ICT (เปิดเครื่อง) ที่ Serial")); Serial.println(i); }
      if (ictStage[i] == 3) { Serial.print(F("   ^ เครื่องรับแบงก์แจ้งมีแบงก์ (ไม่ตอบรับ แบงก์จะคายคืน) Serial")); Serial.println(i); }
      ictStage[i] = 0; portCount[i] = 0;
    }
  }
}

void setup() {
  Serial.begin(115200);
  for (uint8_t i = 1; i <= 3; i++) { PORTS[i]->begin(9600, SERIAL_8E1); txEnable(i, false); }
  setupPins();
  delay(200);
  Serial.println();
  Serial.println(F("==== " FW " เฟิร์มแวร์ตรวจสอบตู้เก็บเงิน ===="));
  Serial.println(F("ทุกขาเป็น INPUT ไม่จ่ายไฟออก / Serial1-3 = 9600 8E1 (รับอย่างเดียว)"));
  help();
  char sig[] = "SIG"; command(sig);
  printState();
}

void loop() {
  pollPins();
  pollPorts();
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\r' || c == '\n') { if (lineLen) { line[lineLen] = 0; Serial.print(F("> ")); Serial.println(line); command(line); lineLen = 0; } }
    else if (lineLen < sizeof(line) - 1) line[lineLen++] = c;
  }
}
