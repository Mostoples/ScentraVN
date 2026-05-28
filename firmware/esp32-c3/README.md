# ESP32-C3 BioCompare Watch Firmware

Reference firmware that exactly matches the GATT contract in
[`shared/protocol/Esp32GattProfile.kt`](../../shared/src/main/java/com/biocompare/shared/protocol/Esp32GattProfile.kt).

## Hardware

| Part           | Address | Notes                                      |
|----------------|---------|--------------------------------------------|
| ESP32-C3       | -       | SuperMini / DevKitM-1 / Seeed XIAO C3 etc. |
| MAX30102       | 0x57    | PPG / heart rate                           |
| MPU6050        | 0x68    | 6-DOF IMU                                  |

Default I2C wiring on most ESP32-C3 boards:

```
Sensor SDA -> ESP32-C3 GPIO5 (SDA)
Sensor SCL -> ESP32-C3 GPIO6 (SCL)
Sensor VCC -> ESP32-C3 3V3
Sensor GND -> ESP32-C3 GND
```

If your board uses different default I2C pins, call `Wire.begin(SDA, SCL)`
explicitly in `initSensors()`.

## Build

### Arduino IDE
1. Install the ESP32 board package (Boards Manager URL: `https://espressif.github.io/arduino-esp32/package_esp32_index.json`).
2. Select board `ESP32C3 Dev Module`, USB CDC On Boot: **Enabled**.
3. Install libraries via Library Manager:
   - NimBLE-Arduino
   - SparkFun MAX3010x Pulse and Proximity Sensor Library
   - Adafruit MPU6050
   - Adafruit Unified Sensor
4. Open `biocompare-watch/biocompare-watch.ino` and Upload.

### PlatformIO
```bash
cd firmware/esp32-c3
pio run -e esp32-c3 -t upload
pio device monitor
```

## BLE Contract

Advertises as **`BioWatch-ESP32`** and exposes:

| Service                       | UUID         | Characteristic                    | Type          |
|-------------------------------|--------------|-----------------------------------|---------------|
| Heart Rate (SIG)              | `0x180D`     | Heart Rate Measurement (`0x2A37`) | Notify        |
| Battery (SIG)                 | `0x180F`     | Battery Level (`0x2A19`)          | Read + Notify |
| Custom IMU                    | `c0de0001-…` | IMU Data (`c0de0002-…`)           | Notify        |
| Custom Control                | `c0de0010-…` | Control Cmd (`c0de0011-…`)        | Write         |

The phone writes a single byte to **Control Cmd** to start (`0x01`) or stop
(`0x00`) streaming. The watch only sends notifications while in the streaming
state; on disconnect it auto-stops and restarts advertising.

## Customising

If your existing firmware already advertises with different UUIDs, change the
constants at the top of `Esp32GattProfile.kt` rather than this firmware --
that is the only place the Android side reads UUIDs, so a one-line edit there
is enough.
