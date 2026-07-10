# KFDDroid

**Version 0.3.1** — Android port of [KFDtool](https://github.com/omahacommsys/KFDtool/tree/master/sw/control)

A P25 Key Fill Device (KFD) application for Android. Load, erase, and manage encryption keys in P25 radios using either a KFD USB adapter (Three-Wire Interface) or a direct IP connection over Bluetooth PAN / USB RNDIS (Data-Link Independent).

---

## Requirements

- Android 8.0 (API 26) or newer
- **TWI mode**: KFDtool or KFDShield USB adapter + USB OTG cable
- **DLI mode**: Bluetooth PAN or USB RNDIS connection to the radio (no adapter needed)

---

## Features

| Feature | TWI | DLI |
|---|:---:|:---:|
| Manual keyload (single key) | ✓ | ✓ |
| Container keyload (`.ekc` file) | ✓ | ✓ |
| Erase key by SLN | ✓ | ✓ |
| Erase all keys (zeroize) | ✓ | ✓ |
| View key info | ✓ | ✓ |
| Device info (adapter) | ✓ | — |

---

## Connection modes

### KFD Adapter (TWI)

Connect a KFDtool or KFDShield adapter via USB OTG. Select **KFD Adapter** on the Connect tab and tap **Connect**. The app requests USB permission automatically on first use.

### DLI over IP

Used with radios that expose a KMM UDP endpoint over Bluetooth PAN or as a USB RNDIS network device. No KFD hardware adapter is required.

1. Connect to the radio's Bluetooth PAN network (pair in Android Bluetooth settings, then connect the PAN profile) or plug in the radio via USB OTG (radio enumerates as a network adapter).
2. Select **DLI over IP** on the Connect tab.
3. Enter the radio's IP address, UDP port, and variant (Standard or Motorola).
   - Motorola APX default: `192.168.132.1 : 49644, Motorola` *(subnet varies — use **Show Network Interfaces** to confirm your phone's IP on the PAN, then set the radio's IP to `.1` of that subnet)*
4. Tap **Set**. The status card turns green when configured.
5. Use **Test** to verify connectivity before attempting a key operation.

> **Note**: The radio must have Bluetooth Data / DLI enabled in its codeplug.

---

## Building

```
git clone https://github.com/your-repo/KFDDroid
```

Open in Android Studio, or build from the command line:

```
./gradlew assembleDebug
```

Output APK: `app/build/outputs/apk/debug/KFDDroid-v0.3.0-debug.apk`

---

## License

This is a port of the KFDtool software published under the MIT License.
Source: https://github.com/omahacommsys/KFDtool/tree/master/sw/control

```
Copyright 2019-2020 KFDtool, LLC
Copyright 2021-2023 Natalie Moore
Copyright 2023 Ilya Smirnov
Copyright 2026 Charles Bricker
```

See individual source files for the full MIT License text.
