// =============================================================================
// BioCompare reference firmware for ESP32-C3 + MAX30102 + MPU6050
// =============================================================================
//
// This sketch is the *reference contract* matched by the Android app's
// Esp32GattProfile.kt. UUIDs, scaling, and packet layout MUST stay in sync
// between the two; if you change one, change the other.
//
// Hardware:
//   - ESP32-C3 SuperMini / DevKitM-1 (any RISC-V C3 board)
//   - MAX30102 PPG sensor   (I2C, default address 0x57)
//   - MPU6050 6-axis IMU    (I2C, default address 0x68)
//   - Optional: status LED on GPIO8
//
// Default I2C wiring (override if your board uses different pins):
//   SDA  -> GPIO5
//   SCL  -> GPIO6
//   3V3  -> sensors VCC
//   GND  -> sensors GND
//
// Libraries (Arduino IDE / Library Manager):
//   - "SparkFun MAX3010x Pulse and Proximity Sensor Library"  (Heart-rate algo)
//   - "Adafruit MPU6050"                                       (IMU)
//   - "Adafruit Unified Sensor"                                (dependency)
//   - NimBLE-Arduino                                           (BLE stack)
//
// Board: ESP32C3 Dev Module, Flash 4MB, USB CDC On Boot: enabled
//
// =============================================================================

#include <Wire.h>
#include <Arduino.h>

#include <NimBLEDevice.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include "MAX30105.h"
#include "heartRate.h"

// --------- BLE UUIDs - MUST match shared/protocol/Esp32GattProfile.kt ---------
#define HR_SERVICE_UUID        "0000180d-0000-1000-8000-00805f9b34fb"
#define HR_MEASUREMENT_UUID    "00002a37-0000-1000-8000-00805f9b34fb"
#define BATTERY_SERVICE_UUID   "0000180f-0000-1000-8000-00805f9b34fb"
#define BATTERY_LEVEL_UUID     "00002a19-0000-1000-8000-00805f9b34fb"
#define IMU_SERVICE_UUID       "c0de0001-1aaa-4bbb-8ccc-1234567890ab"
#define IMU_DATA_UUID          "c0de0002-1aaa-4bbb-8ccc-1234567890ab"
#define CONTROL_SERVICE_UUID   "c0de0010-1aaa-4bbb-8ccc-1234567890ab"
#define CONTROL_CMD_UUID       "c0de0011-1aaa-4bbb-8ccc-1234567890ab"

// Advertised name. Android filters by this prefix.
static const char* DEVICE_NAME = "BioWatch-ESP32";

// --------- Stream cadence (Hz) ---------
static const uint32_t IMU_PERIOD_MS = 20;     // 50 Hz
static const uint32_t HR_PERIOD_MS  = 1000;   // 1 Hz
static const uint32_t BATT_PERIOD_MS = 30000; // 0.033 Hz

// --------- Globals ---------
NimBLECharacteristic* hrChar     = nullptr;
NimBLECharacteristic* imuChar    = nullptr;
NimBLECharacteristic* battChar   = nullptr;
NimBLECharacteristic* ctrlChar   = nullptr;
NimBLEServer*         server    = nullptr;

Adafruit_MPU6050 mpu;
MAX30105 ppg;

volatile bool streaming   = false;
volatile bool connected   = false;
uint32_t lastImuMs = 0, lastHrMs = 0, lastBattMs = 0;
uint16_t devTimestamp = 0;

// HR algorithm state (SparkFun beat detector).
static const uint8_t RATE_SIZE = 4;
uint8_t rates[RATE_SIZE] = {0};
uint8_t rateSpot = 0;
long lastBeatMs = 0;
float beatsPerMinute = 0;
uint8_t beatAvg = 0;

// =============================================================================
// BLE callbacks
// =============================================================================

class ServerCb : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* s, NimBLEConnInfo&) override {
    connected = true;
    Serial.println("[BLE] Client connected");
  }
  void onDisconnect(NimBLEServer* s, NimBLEConnInfo&, int) override {
    connected = false;
    streaming = false;
    Serial.println("[BLE] Client disconnected; restarting advertising");
    NimBLEDevice::startAdvertising();
  }
};

/**
 * Control characteristic: phone writes 1 byte to start/stop streaming.
 *   0x00 = stop streaming
 *   0x01 = start streaming
 *   0x02 = ping (keepalive, no-op)
 */
class CtrlCb : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
    std::string v = c->getValue();
    if (v.empty()) return;
    uint8_t cmd = (uint8_t)v[0];
    Serial.printf("[CTRL] cmd=0x%02X\n", cmd);
    if (cmd == 0x01) streaming = true;
    else if (cmd == 0x00) streaming = false;
    else if (cmd == 0x02) {/* ping */}
  }
};

// =============================================================================
// Sensor I/O
// =============================================================================

bool initSensors() {
  Wire.begin();  // default I2C pins for the board
  Wire.setClock(400000);

  if (!mpu.begin()) {
    Serial.println("[ERR] MPU6050 not found");
    return false;
  }
  mpu.setAccelerometerRange(MPU6050_RANGE_2_G);
  mpu.setGyroRange(MPU6050_RANGE_250_DEG);
  mpu.setFilterBandwidth(MPU6050_BAND_44_HZ);

  if (!ppg.begin(Wire, I2C_SPEED_FAST)) {
    Serial.println("[ERR] MAX30102 not found");
    return false;
  }
  // Recommended HR-only config from SparkFun example:
  ppg.setup(0x1F, 4, 2, 400, 411, 4096);
  ppg.setPulseAmplitudeRed(0x0A);
  ppg.setPulseAmplitudeGreen(0);

  return true;
}

/**
 * Build the Bluetooth SIG Heart Rate Measurement (0x2A37) packet.
 * Layout: [flags][bpm uint8].  Flags bit0=0 -> uint8 bpm.
 */
void notifyHeartRate(uint8_t bpm) {
  uint8_t buf[2];
  buf[0] = 0x00;          // flags: uint8 BPM, no contact info, no energy expended
  buf[1] = bpm;
  hrChar->setValue(buf, sizeof(buf));
  hrChar->notify();
}

/**
 * 14-byte IMU packet (little-endian):
 *   [0..1]  int16  ax_raw  ( ±32767, scale 16384/g )
 *   [2..3]  int16  ay_raw
 *   [4..5]  int16  az_raw
 *   [6..7]  int16  gx_raw  ( scale 131 / (deg/s) )
 *   [8..9]  int16  gy_raw
 *   [10..11] int16 gz_raw
 *   [12..13] uint16 device timestamp ms (wraps every 65 s)
 *
 * Adafruit_MPU6050 returns SI units (m/s², rad/s); we convert back to raw
 * counts using the scaling constants for ±2g / ±250°/s so the Android side
 * can use a single canonical scaling table.
 */
void notifyImu(const sensors_event_t& a, const sensors_event_t& g) {
  const float ACCEL_LSB_PER_G = 16384.0f;
  const float G = 9.80665f;
  const float GYRO_LSB_PER_DPS = 131.0f;
  const float DEG_PER_RAD = 57.2957795f;

  int16_t ax = (int16_t)((a.acceleration.x / G) * ACCEL_LSB_PER_G);
  int16_t ay = (int16_t)((a.acceleration.y / G) * ACCEL_LSB_PER_G);
  int16_t az = (int16_t)((a.acceleration.z / G) * ACCEL_LSB_PER_G);
  int16_t gx = (int16_t)((g.gyro.x * DEG_PER_RAD) * GYRO_LSB_PER_DPS);
  int16_t gy = (int16_t)((g.gyro.y * DEG_PER_RAD) * GYRO_LSB_PER_DPS);
  int16_t gz = (int16_t)((g.gyro.z * DEG_PER_RAD) * GYRO_LSB_PER_DPS);

  uint8_t buf[14];
  buf[0]=ax; buf[1]=ax>>8;
  buf[2]=ay; buf[3]=ay>>8;
  buf[4]=az; buf[5]=az>>8;
  buf[6]=gx; buf[7]=gx>>8;
  buf[8]=gy; buf[9]=gy>>8;
  buf[10]=gz; buf[11]=gz>>8;
  buf[12]=devTimestamp; buf[13]=devTimestamp>>8;
  imuChar->setValue(buf, sizeof(buf));
  imuChar->notify();
}

void notifyBattery(uint8_t pct) {
  battChar->setValue(&pct, 1);
  battChar->notify();
}

/**
 * Returns BPM after the SparkFun beat detector has accumulated enough peaks.
 * Returns 0 until then.
 */
uint8_t computeHeartRate() {
  long ir = ppg.getIR();
  if (ir < 50000) {
    // No finger / poor contact.
    return 0;
  }
  if (checkForBeat(ir)) {
    long delta = millis() - lastBeatMs;
    lastBeatMs = millis();
    beatsPerMinute = 60.0f / (delta / 1000.0f);
    if (beatsPerMinute > 30 && beatsPerMinute < 220) {
      rates[rateSpot++] = (uint8_t)beatsPerMinute;
      rateSpot %= RATE_SIZE;
      uint16_t sum = 0;
      for (uint8_t i = 0; i < RATE_SIZE; i++) sum += rates[i];
      beatAvg = sum / RATE_SIZE;
    }
  }
  return beatAvg;
}

/**
 * Replace this with an ADC read on the Vbat divider for your board. As a
 * placeholder we ramp 100 -> 0 over 10 minutes so the dashboard still has a
 * non-trivial battery value during a demo.
 */
uint8_t readBatteryPercent() {
  uint32_t s = millis() / 6000;          // step every 6 s
  return (uint8_t)max(0, 100 - (int)s);
}

// =============================================================================
// Setup / loop
// =============================================================================

void setupBle() {
  NimBLEDevice::init(DEVICE_NAME);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);
  NimBLEDevice::setMTU(247);

  server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCb());

  // Heart Rate Service (standard SIG)
  auto* hrSvc = server->createService(HR_SERVICE_UUID);
  hrChar = hrSvc->createCharacteristic(
    HR_MEASUREMENT_UUID,
    NIMBLE_PROPERTY::NOTIFY
  );
  hrSvc->start();

  // Battery Service (standard SIG)
  auto* battSvc = server->createService(BATTERY_SERVICE_UUID);
  battChar = battSvc->createCharacteristic(
    BATTERY_LEVEL_UUID,
    NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY
  );
  uint8_t initBatt = 100;
  battChar->setValue(&initBatt, 1);
  battSvc->start();

  // Custom IMU Service
  auto* imuSvc = server->createService(IMU_SERVICE_UUID);
  imuChar = imuSvc->createCharacteristic(
    IMU_DATA_UUID,
    NIMBLE_PROPERTY::NOTIFY
  );
  imuSvc->start();

  // Custom Control Service
  auto* ctrlSvc = server->createService(CONTROL_SERVICE_UUID);
  ctrlChar = ctrlSvc->createCharacteristic(
    CONTROL_CMD_UUID,
    NIMBLE_PROPERTY::WRITE
  );
  ctrlChar->setCallbacks(new CtrlCb());
  ctrlSvc->start();

  // Advertise both standard services so a generic BLE scanner can find us.
  auto* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(HR_SERVICE_UUID);
  adv->addServiceUUID(IMU_SERVICE_UUID);
  adv->setName(DEVICE_NAME);
  adv->start();
  Serial.printf("[BLE] Advertising as %s\n", DEVICE_NAME);
}

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println();
  Serial.println("BioCompare ESP32-C3 firmware booting…");

  if (!initSensors()) {
    Serial.println("[FATAL] sensor init failed; halting");
    while (true) delay(1000);
  }

  setupBle();
  Serial.println("Ready.");
}

void loop() {
  uint32_t now = millis();
  devTimestamp = (uint16_t)now;

  // Always poll the PPG so the beat detector keeps state warm even when not
  // streaming -- avoids long startup latency when the phone hits Start.
  uint8_t bpm = computeHeartRate();

  if (streaming && connected) {
    if (now - lastImuMs >= IMU_PERIOD_MS) {
      lastImuMs = now;
      sensors_event_t a, g, t;
      mpu.getEvent(&a, &g, &t);
      notifyImu(a, g);
    }
    if (now - lastHrMs >= HR_PERIOD_MS) {
      lastHrMs = now;
      if (bpm > 0) notifyHeartRate(bpm);
    }
    if (now - lastBattMs >= BATT_PERIOD_MS) {
      lastBattMs = now;
      notifyBattery(readBatteryPercent());
    }
  }
  delay(2);
}
