# ShareDrop — Android Wi-Fi Direct File Sharing

> **Final Year Project** | An Android equivalent of Apple AirDrop — wireless peer-to-peer file sharing with no internet connection required.

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [System Requirements](#system-requirements)
3. [Technology Stack & Justification](#technology-stack--justification)
4. [Architecture](#architecture)
5. [TCP Consent Protocol](#tcp-consent-protocol)
6. [Project Setup & Build](#project-setup--build)
7. [Running on Physical Devices](#running-on-physical-devices)
8. [Demo Walkthrough](#demo-walkthrough)
9. [File Structure](#file-structure)
10. [Known Limitations & Future Work](#known-limitations--future-work)

---

## Project Overview

ShareDrop allows two nearby Android devices to:

- **Discover** each other over Wi-Fi Direct (IEEE 802.11p2p) — no Wi-Fi router or internet connection required.
- **Connect** via a direct device-to-device wireless link negotiated by the Android OS.
- **Transfer** any file (photos, videos, documents, APKs, etc.) using a raw TCP socket stream.
- **Consent** — the recipient is always shown a dialog with the file name and size before any bytes are written to disk. They can Accept or Decline.

The app is analogous to Apple AirDrop but built entirely with Android's native APIs in Java.

---

## System Requirements

### Development Machine

| Requirement | Minimum |
|-------------|---------|
| Operating System | Windows 10 / macOS 12 / Ubuntu 20.04 |
| Android Studio | Hedgehog 2023.1.1 or newer |
| Java Development Kit | JDK 8 (bundled with Android Studio) |
| Android SDK | API 34 (Android 14) — install via SDK Manager |
| Build Tools | 34.0.0 |
| Gradle | 8.4 (downloaded automatically on first sync) |

### Test Devices (both required — emulators cannot do Wi-Fi Direct)

| Requirement | Details |
|-------------|---------|
| OS Version | Android 6.0 (API 23) or higher |
| Recommended OS | Android 9, 10, or 11 for most reliable Wi-Fi Direct behaviour |
| Wi-Fi Direct hardware | Must be supported (virtually all Android devices from 2012 onward) |
| USB Debugging | Enabled: Settings → Developer Options → USB Debugging |
| Wi-Fi | Enabled — does NOT need to be connected to a router |
| Location services | Enabled on Android 6–12 (required by the OS for peer discovery) |

---

## Technology Stack & Justification

### Language: Java (not Kotlin)

Java was chosen because:

- **Academic familiarity** — the Android Java APIs are extensively documented in official Android developer guides and in the academic literature this project references.
- **Explicit threading model** — `Thread`, `Runnable`, and `synchronized` blocks make the concurrency behaviour visible and easy to explain in a dissertation, compared to Kotlin coroutines / Flow which hide scheduling details.
- **No hidden abstractions** — Java 8 IO streams, try-with-resources, and `AtomicBoolean` are standard library primitives. Every line of concurrent code can be traced to a JVM specification clause.

### Wireless Technology: Wi-Fi Direct (IEEE 802.11p2p)

Wi-Fi Direct was the only technology that satisfies all project requirements simultaneously:

| Technology | No internet | No router | High throughput | OS-level API | Explicit consent UX |
|------------|:-----------:|:---------:|:---------------:|:------------:|:-------------------:|
| **Wi-Fi Direct** | ✅ | ✅ | ✅ (~250 Mbps) | ✅ `WifiP2pManager` | ✅ |
| Bluetooth Classic | ✅ | ✅ | ❌ (~3 Mbps) | ✅ | ✅ |
| BLE | ✅ | ✅ | ❌ (tiny MTU) | ✅ | — |
| NFC | ✅ | ✅ | ❌ (tiny payload) | ✅ | — |
| Local Wi-Fi (same AP) | ❌ | ❌ | ✅ | — | — |

Wi-Fi Direct creates an independent 2.4 / 5 GHz network between the devices: one device becomes a software access point (the **Group Owner**), the other connects as a client. Throughput is limited only by the Wi-Fi adapters — large video files transfer in seconds rather than minutes.

### File Transfer: TCP Sockets (`java.net.ServerSocket` / `java.net.Socket`)

- **Reliability** — TCP guarantees ordered, error-checked delivery. Unlike UDP there is no need to implement retransmission, ordering, or checksumming at the application layer.
- **Simplicity** — a raw socket stream maps directly onto the Java `InputStream`/`OutputStream` model, making it straightforward to read/write in an 8 KB loop and report progress.
- **Single connection, dual-phase** — the same socket carries the text consent handshake and then the binary file payload. This avoids the complexity of a separate control connection.
- **8 KB buffer** — a well-studied sweet spot: large enough to amortise system-call overhead, small enough to avoid excessive memory pressure on constrained devices.

### UI: Android XML Layouts + Material Components 3

- **Material Design 3** components (`MaterialCardView`, `MaterialButton`, `AppBarLayout`) provide a professional appearance with minimal custom drawing code.
- **RecyclerView** handles variable-length peer lists and received-file lists efficiently via view recycling.
- **AlertDialog** for the consent prompt gives a modal, focused interaction — important because accepting a file has storage implications.
- **CoordinatorLayout + NestedScrollView** allow the peer list and transfer controls to scroll together as a single logical screen.

### Build System: Gradle (AGP 8.2.2, Gradle 8.4)

- Industry-standard build system for Android; required by Android Studio.
- Declarative dependency management: adding a library is a single `implementation` line.
- The `namespace` DSL (AGP 7.3+) is used instead of `packageName` in the manifest, following current best practices.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   Presentation Layer                     │
│  MainActivity · ReceivedFilesActivity                    │
│  DeviceListAdapter · ReceivedFilesAdapter                │
│  XML layouts (activity_main, item_device, …)             │
└────────────────────────┬────────────────────────────────┘
                         │  callbacks / runOnUiThread()
┌────────────────────────▼────────────────────────────────┐
│               Application Logic Layer                    │
│  WiFiDirectBroadcastReceiver  (4 P2P system intents)     │
│  FileTransferService.FileServerThread  (receiver)        │
│  FileTransferService.FileClientThread  (sender)          │
│  Consent synchronisation  (Object.wait / notifyAll)      │
└────────────────────────┬────────────────────────────────┘
                         │  Android OS APIs
┌────────────────────────▼────────────────────────────────┐
│                 Communication Layer                      │
│  WifiP2pManager  ←→  IEEE 802.11 P2P radio               │
│  ServerSocket / Socket  ←→  TCP/IP over P2P group        │
└─────────────────────────────────────────────────────────┘
```

### Role Assignment After Group Formation

```
Device A ──── WifiP2pManager.connect() ────► Device B
         ◄─ 802.11 GO negotiation frames ──►
              OS assigns roles automatically
         ──── WIFI_P2P_CONNECTION_CHANGED_ACTION ────►
              WifiP2pInfo.isGroupOwner = true / false
              WifiP2pInfo.groupOwnerAddress = GO's IP
```

| Role | Behaviour in ShareDrop | IP Address |
|------|------------------------|------------|
| Group Owner (GO) | Runs `FileServerThread` on port 8888; waits to receive | Always `192.168.49.1` |
| Client (non-GO) | Connects to GO's IP; sends selected file | DHCP-assigned by GO |

---

## TCP Consent Protocol

The entire transfer (consent + data) flows over a **single TCP connection** to keep the implementation simple and avoid synchronisation between multiple sockets.

```
CLIENT (sender)                            SERVER/GO (receiver)
────────────────────────────────────────────────────────────────
connect()  ──────────────────────────────►  accept()
                                            setSoTimeout(0)   // no timeout while awaiting consent

write "FILENAME|FILESIZE\n"  (UTF-8) ────►  readLine()  [byte-by-byte, no buffering]
                                            │
                                            ▼
                                           show AlertDialog
                                           block on consentLock.wait() (≤60 s)
                                            │
                                            ▼  user taps Accept or Decline
◄──────── "ACCEPT\n" or "DECLINE\n" ──────  write response
                                            setSoTimeout(30 000)  // re-arm for data

readLine() → if "DECLINE":
  notifyUI, close socket                ◄──  close socket
  ← done ─────────────────────────────────────────────────────

readLine() → if "ACCEPT":
  open ContentResolver InputStream
  loop:
    n = read(buf, 0, 8192)
    write(socket_out, buf, 0, n) ───────►  loop:
    sent += n                                n = read(socket_in, buf, …)
    onProgress(sent*100/total, …)            write(FileOutputStream, buf, 0, n)
                                             received += n
                                             onProgress(received*100/total, …)
  close socket ───────────────────────────►  close socket
                                             onTransferComplete(savedPath)
```

**Why byte-by-byte line reading?**  
A `BufferedReader` fills an 8 KB internal buffer on the first `readLine()` call, consuming the initial bytes of the file payload. By reading one byte at a time and stopping at `'\n'`, the `InputStream` is left positioned exactly at the first file byte.

---

## Project Setup & Build

### 1. Clone the Repository

```bash
git clone https://github.com/hybridthegamer/android-wireless.git
cd android-wireless
```

### 2. Open in Android Studio

1. Launch **Android Studio** (Hedgehog 2023.1.1 or newer).
2. **File → Open** → select the `android-wireless` folder.
3. Click **Sync Now** in the Gradle notification bar.
4. Gradle downloads all dependencies (~100 MB on first sync).

### 3. Install the Required SDK (if not already present)

Open **Tools → SDK Manager**:

- ✅ Android 14.0 (API 34) — SDK Platform
- ✅ Android SDK Build-Tools 34.0.0
- ✅ Android Emulator (optional — only for UI testing; Wi-Fi Direct requires real hardware)

### 4. Build the Debug APK

```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

APK output path:

```
app/build/outputs/apk/debug/app-debug.apk
```

---

## Running on Physical Devices

### Prepare Both Devices

```
Settings → About Phone → tap "Build Number" seven times  →  Developer Options unlocked
Settings → Developer Options → USB Debugging  →  ON
Settings → Wi-Fi  →  ON  (no router needed)
Settings → Location  →  ON  (required on Android 6–12)
```

### Install via Android Studio

1. Connect **Device A** via USB → click **Run** (▶) → select Device A → app installs and launches.
2. Disconnect Device A, connect **Device B** → click **Run** again → install on Device B.

Or via ADB directly:

```bash
adb -s <device-serial> install app/build/outputs/apk/debug/app-debug.apk
```

### Grant Permissions at First Launch

| Android Version | Permission requested |
|-----------------|---------------------|
| 6–12 | ACCESS_FINE_LOCATION + READ_EXTERNAL_STORAGE |
| 13+ | NEARBY_WIFI_DEVICES |

Tap **Allow** on both devices.

---

## Demo Walkthrough

> Follow this sequence on demo day for a smooth, repeatable presentation.

### Step 1 — Launch

Open **ShareDrop** on both phones.  
Status bar: `"Wi-Fi Direct: ON"` ✅

### Step 2 — Discover (Device A)

On **Device A**: tap **"Discover Nearby Devices"**.  
Status: *"Discovery started — looking for nearby devices…"*  
After 2–5 seconds Device B appears in the list.

### Step 3 — Connect

Tap **Device B**'s name in the list on Device A.  
Status updates to *"Connection request sent…"* then *"Connected"*.

Roles are displayed:
- One device → **"Role: Receiver (Group Owner)"**
- Other device → **"Role: Sender (Client)"**

### Step 4 — Select & Send (Sender device)

1. Tap **"Select File to Send"** → choose any file.
2. File name and size appear.
3. Tap **"Send File"**.

### Step 5 — Consent (Receiver device)

An alert dialog appears:

```
📥 Incoming Transfer
From device: 192.168.49.xxx
File: holiday_photo.jpg
Size: 4.27 MB
                      [Decline]  [Accept]
```

Tap **Accept**.

### Step 6 — Transfer Progress

Both devices display a live progress bar:

```
Sending: 2.10 MB / 4.27 MB  (49 %)
```

### Step 7 — Completion

On the receiver a dialog confirms the save path. Tap **"View Files"** to open the received-files screen with the file listed (name, size, timestamp). Tap the file row to open it in the system viewer.

### Step 8 — Reset for Next Demo

Tap the red **"Disconnect"** button → both devices return to *"Not connected"*.  
Tap **"Rescan"** on either device to start fresh discovery.

---

## File Structure

```
android-wireless/
├── README.md
├── build.gradle                              # Project-level (AGP version)
├── settings.gradle                           # Module list + repository config
├── gradle.properties                         # JVM args, AndroidX enablement
├── gradle/wrapper/
│   └── gradle-wrapper.properties            # Gradle 8.4 distribution URL
└── app/
    ├── build.gradle                          # compileSdk 34, minSdk 23, dependencies
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml               # Permissions, activities, FileProvider
        ├── java/com/example/sharedrop/
        │   ├── MainActivity.java             # Wi-Fi Direct init, peer list, role assignment,
        │   │                                 # consent dialog, send/receive UI
        │   ├── WiFiDirectBroadcastReceiver.java  # Handles 4 P2P system broadcasts
        │   ├── FileTransferService.java      # FileServerThread + FileClientThread
        │   │                                 # + TCP consent protocol implementation
        │   ├── DeviceListAdapter.java        # RecyclerView adapter — discovered peers
        │   ├── ReceivedFilesActivity.java    # Received file list + FileProvider open
        │   └── ReceivedFilesAdapter.java     # RecyclerView adapter — received files
        └── res/
            ├── layout/
            │   ├── activity_main.xml         # Main screen (toolbar, peer list, transfer card)
            │   ├── activity_received_files.xml
            │   ├── item_device.xml           # Single peer row
            │   └── item_received_file.xml    # Single received-file row
            ├── values/
            │   ├── strings.xml
            │   ├── colors.xml               # Deep blue #1565C0 + accent orange #FF6D00
            │   └── themes.xml               # Theme.MaterialComponents.DayNight.NoActionBar
            ├── drawable/
            │   ├── ic_launcher.xml
            │   ├── ic_launcher_round.xml
            │   ├── ic_device_circle.xml      # Oval background for device icons
            │   └── bg_status_badge.xml       # Pill background for status labels
            └── xml/
                └── file_provider_paths.xml   # FileProvider path declarations
```

### Received Files Location

```
/sdcard/Android/data/com.example.sharedrop/files/Downloads/ShareDrop/
```

Served to other apps as a `content://` URI via `FileProvider` — no `READ_EXTERNAL_STORAGE` permission needed from the receiving app.

---

## Known Limitations & Future Work

### Current Limitations (MVP Scope)

| Limitation | Notes |
|------------|-------|
| **Unidirectional per session** | GO is always receiver. Disconnect and reconnect to swap roles — the OS may elect a different GO. |
| **One file at a time** | Server re-enters `accept()` after each transfer. Queueing is a natural extension. |
| **GO IP assumed static** | The Android framework always gives the GO `192.168.49.1`; this is documented behaviour, not a hack. |
| **No encryption in transit** | Files travel in plaintext over the P2P link. Replace `Socket` with `SSLSocket` for TLS. |
| **No transfer resume** | Interrupted transfers must restart. A byte-range offset stored in a sidecar file would fix this. |

### Planned Enhancements

- **Bidirectional** — after the GO learns the client's IP from the accepted TCP connection, it can open a return channel for GO→Client transfers without disconnecting.
- **Multi-file queue** — pipeline transfers using `BlockingQueue<FileDescriptor>`.
- **TLS encryption** — self-signed certificate pair exchanged during the P2P handshake.
- **QR code pairing** — encode device MAC in a QR code so the initiator can scan rather than browse a list.
- **Transfer speed indicator** — track bytes/millisecond over a sliding 1-second window.
- **Folder transfer** — stream a ZIP archive generated on the fly from a selected directory.

---

## Acknowledgements

- Android Wi-Fi Direct developer guide: <https://developer.android.com/training/connect-devices-wirelessly/wifi-direct>
- IEEE 802.11-2020 specification (Wi-Fi Alliance Wi-Fi Direct Technical Specification v1.7)
- Material Design 3 component library: <https://m3.material.io>

---

*Built with Java · Wi-Fi Direct (IEEE 802.11p2p) · TCP Sockets · Material Design 3 · minSdkVersion 23*
