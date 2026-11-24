# AVR Home Automation — Bluetooth Control System

An enterprise-grade, modern Android application written in **Java** to monitor and control an **ATmega32 AVR Microcontroller Home Automation System** via an **HC-05 Classic Bluetooth (SPP)** module.

This repository includes a completely configured **GitHub Actions CI/CD workflow** that automatically builds and exports `app-debug.apk` directly in the cloud—**no local Android Studio or local Android SDK installation is required**.

---

## 👨‍💻 Project Authors & Credits
- **Mohamed Tapl**
- **Mahmod Yahia**
- **Project Focus:** Embedded Systems — AVR Microcontroller Graduation Project

---

## 📱 Features & Highlights

- **Modern Material 3 Dashboard:** Elevated white cards, cohesive Indigo/Teal/Amber accent palette, dynamic active state badges, and smooth touch feedback.
- **Classic Bluetooth (SPP) Engine:** Built on standard RFCOMM UUID `00001101-0000-1000-8000-00805F9B34FB` with non-blocking connection threads (`ExecutorService`).
- **Comprehensive Android Permissions:** Full dual-stack support for modern Android 12+ (API 31–34 `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`) and legacy Android (API 24–30 `ACCESS_FINE_LOCATION`, `BLUETOOTH`, `BLUETOOTH_ADMIN`).
- **Real-Time Telemetry & Monospace UART Terminal:** A fixed 220dp console (`#12161F` background, `#00E676` green monospace output) with timestamped `[TX]` commands and `[RX]` responses.
- **Safety Alarms:** Automatic detection of `"ERR:MANUAL_LOCK"` responses from firmware, triggering immediate UI warnings and toasts.
- **Zero-Setup Cloud Build:** Pre-configured GitHub Actions workflow automatically compiles and publishes `app-debug.apk` as a downloadable artifact.

---

## 📡 ATmega32 UART Protocol & Command Reference

The ATmega32 communicates over hardware USART at **9600 Baud, 8 Data Bits, 1 Stop Bit, No Parity**.

### 1. ASCII Command Mapping (App -> ATmega32)

| ASCII Command | Hex Code | Appliance / Target | Action Description |
| :---: | :---: | :--- | :--- |
| **`L`** | `0x4C` | Room Light (Relay 1 + LED) | Turn ON Room Light |
| **`l`** | `0x6C` | Room Light (Relay 1 + LED) | Turn OFF Room Light |
| **`R`** | `0x52` | Auxiliary Appliance (Relay 2) | Turn ON Auxiliary Socket / Load |
| **`r`** | `0x72` | Auxiliary Appliance (Relay 2) | Turn OFF Auxiliary Socket / Load |
| **`F`** | `0x46` | Fan Control (DC Motor) | Master Fan Turn ON |
| **`f`** | `0x66` | Fan Control (DC Motor) | Master Fan Turn OFF |
| **`0`** | `0x30` | Fan PWM Speed | Preset Speed: **0% (Halted)** |
| **`1`** | `0x31` | Fan PWM Speed | Preset Speed: **40% (Low)** |
| **`2`** | `0x32` | Fan PWM Speed | Preset Speed: **70% (Medium)** |
| **`3`** | `0x33` | Fan PWM Speed | Preset Speed: **100% (Maximum)** |
| **`S`** | `0x53` | System Telemetry & EEPROM | Request Current States + Last 5 EEPROM Logs |

### 2. Microcontroller Responses (ATmega32 -> App)

Incoming lines are continuously read via `BufferedReader` on a background thread and rendered in the terminal:
- `LIGHT: ON` or `LIGHT: OFF`
- `AUX: ON` or `AUX: OFF`
- `FAN: ON` or `FAN: OFF`
- `SPEED: 0%`, `SPEED: 40%`, `SPEED: 70%`, `SPEED: 100%`
- `--- EEPROM LOGS ---` (displays last 5 historical operations stored in ATmega32 internal EEPROM)
- `ERR:MANUAL_LOCK` (triggers red warning text and user toast if hardware wall switches have locked the system)

---

## 🔌 Hardware Setup & Interfacing (ATmega32 + HC-05)

### 1. HC-05 Bluetooth Module Pinout
| HC-05 Pin | ATmega32 Pin | Description / Notes |
| :--- | :--- | :--- |
| **VCC** | `+5V` (VCC) | Power supply |
| **GND** | `GND` | Common ground reference |
| **TXD** | **PD0 (RXD)** (Pin 14) | Transmits serial data to ATmega32 USART receiver |
| **RXD** | **PD1 (TXD)** (Pin 15) | Receives data from ATmega32 via voltage divider (5V -> 3.3V) |
| **STATE** | Optional | High when Bluetooth is paired/connected |
| **EN / KEY**| Float / GND | Set LOW for normal transparent UART communication mode |

> **Important (Voltage Divider for HC-05 RX):**  
> While the ATmega32 operates at 5V, the HC-05 RXD pin is 3.3V tolerant. Use a 1kΩ / 2kΩ resistor divider between ATmega32 PD1 (TXD) and HC-05 RXD to guarantee long-term hardware reliability.

### 2. Appliance Actuators & Peripheral Interfacing
| Component | ATmega32 Pin | Interface Type |
| :--- | :--- | :--- |
| **Relay 1 (Room Light)** | `PC2` (or configured GPIO) | Optocoupler Relay Module / ULN2003 Driver |
| **Relay 2 (Auxiliary)** | `PC3` (or configured GPIO) | Optocoupler Relay Module / ULN2003 Driver |
| **DC Motor Fan (PWM)** | `PB3 (OC0)` or `PD5 (OC1A)` | L298N / L293D / MOSFET Motor Driver |
| **Status LEDs** | `PC0`, `PC1` | Current limiting 330Ω resistors |

---

## 🚀 Building the APK via GitHub Actions (Without Android Studio)

You do **not** need Android Studio installed locally. The project compiles automatically using GitHub Actions:

1. **Push this repository to GitHub:**
   ```bash
   git init
   git add .
   git commit -m "Initial commit of AVR Bluetooth Home Automation App"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<your-repo-name>.git
   git push -u origin main
   ```

2. **Trigger the Cloud Build:**
   - Every push to `main` or `master` automatically starts the workflow defined in `.github/workflows/build-apk.yml`.
   - Alternatively, navigate to the **Actions** tab on your GitHub repository, select **"Build & Export APK"**, and click **"Run workflow"**.

3. **Download Your APK:**
   - Once the build finishes (takes ~1-2 minutes), click on the completed workflow run.
   - Under the **Artifacts** section at the bottom of the page, download **`AVR-Home-Automation-APK.zip`**.
   - Unzip the file to extract **`app-debug.apk`**, copy it to your Android device, and install it!

---

## 📁 Repository Directory Structure

```text
AVR_Bluetooth_App/
├── .github/
│   └── workflows/
│       └── build-apk.yml            # CI workflow compiling app-debug.apk
├── app/
│   ├── build.gradle                 # Module build configuration (SDK 34, Java 17)
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml  # Complete Bluetooth permissions (API 24-34)
│           ├── java/com/avr/homeautomation/
│           │   ├── BluetoothManager.java  # SPP Bluetooth connection & async streams
│           │   └── MainActivity.java      # Dashboard UI, protocol logic & telemetry
│           └── res/
│               ├── drawable/
│               │   ├── bg_status_badge.xml
│               │   ├── bg_terminal.xml
│               │   ├── bg_developer_card.xml
│               │   ├── ic_bluetooth.xml
│               │   ├── ic_light.xml
│               │   ├── ic_power.xml
│               │   ├── ic_fan.xml
│               │   ├── ic_terminal.xml
│               │   ├── ic_refresh.xml
│               │   ├── ic_clear.xml
│               │   └── ic_developer.xml
│               ├── layout/
│               │   └── activity_main.xml  # Material 3 smart home layout
│               └── values/
│                   ├── colors.xml         # Modern Indigo/Teal/Amber palette
│                   ├── strings.xml        # Localization & labels
│                   └── themes.xml         # Material 3 DayNight theme
├── build.gradle                     # Top-level Gradle configuration (AGP 8.5.2)
├── gradle.properties                # AndroidX, Jetifier & JVM tuning
├── settings.gradle                  # Repository & project module settings
└── README.md                        # Documentation & project manual
```

---

## 🔒 Security & Best Practices

- **Resource Management:** `BluetoothSocket` and streaming threads are safely terminated in `onDestroy()` and on unpairing to prevent memory/socket leaks.
- **Main-Thread Safety:** All asynchronous Bluetooth I/O is isolated on worker executors, with UI updates safely dispatched via `Looper.getMainLooper()`.
- **Runtime Permissions:** Smooth fallback between Android 12+ fine-grained Bluetooth permissions and legacy location-based Bluetooth discovery.
