# KFDMobile

**Version 0.6.4**. Android port of [KFDtool](https://github.com/omahacommsys/KFDtool/tree/master/sw/control).

A P25 Key Fill Device application for Android phones and tablets. Load, erase, and manage encryption keys in P25 subscriber units using a KFD USB adapter (three-wire interface) or a direct network connection to the radio (Data Link Independent). Also emulates a radio so that a KVL can push keys to your phone for saving into an encrypted container.

---

## Requirements

- Android 8.0 (API 26) or newer
- One of:
  - **KFD Adapter (TWI)**: KFDtool or KFDShield adapter with a USB OTG cable
  - **DLI over Bluetooth PAN**: a Motorola or other P25 radio with DLI enabled in its codeplug, paired to the phone
  - **DLI over USB**: a Motorola APX portable, mobile, or control head that exposes its radio-network profile over USB, connected with an OTG cable

---

## Features

| Feature                                       | TWI | DLI |
|-----------------------------------------------|:---:|:---:|
| Manual keyload (single key)                   |  Y  |  Y  |
| Container keyload (encrypted `.ekc` file)     |  Y  |  Y  |
| Erase key by SLN                              |  Y  |  Y  |
| Erase all keys (zeroize)                      |  Y  |  Y  |
| View active keys in the radio                 |  Y  |  Y  |
| Device info (adapter version, model, serial)  |  Y  |     |
| MR emulator (receive keys from a KVL)         |  Y  |     |

Additional application features:

- Password-protected `.ekc` container files compatible with the desktop KFDtool format
- On-the-fly random key generation, including DES parity fixup
- Real-time DES parity checking on entered keys
- Multi-select keyload from a container
- Radio auto-detection when a supported USB radio is plugged in

---

## Supported algorithms

- AES-256 and AES-128
- DES-OFB and DES-XL, with parity validation
- Triple-DES
- ADP (RC4)
- Any user-supplied algorithm ID, entered as hex, with no length restriction

---

## Connection modes

### KFD Adapter (TWI)

Connect a KFDtool or KFDShield adapter through a USB OTG cable. Select `KFD Adapter` on the Connect tab and tap `Connect`. The app requests USB permission automatically the first time. Once the adapter is identified, the status banner shows either `Original KFDtool` or `KFDShield/Other` along with the adapter protocol version.

### DLI over Bluetooth PAN

For radios that expose a KMM UDP endpoint over Bluetooth Personal Area Network:

1. Pair the radio with the phone in Android Bluetooth settings.
2. Connect the PAN profile in Bluetooth device details. On some phones you also need to enable Airplane Mode with Bluetooth left on so that Android routes traffic to the PAN interface rather than mobile data or Wi-Fi.
3. On the Connect tab, switch to `DLI` and tap `Configure`.
4. Enter the radio's IP address, UDP port, and select `Motorola` if this is a Motorola MFID 0x90 device.
5. Tap `Save`. The green banner shows the configured host.
6. Tap `Test`. On success, the app runs a radio compatibility check to confirm the platform supports software keyload.

### DLI over USB (Motorola APX only)

For Motorola APX radios, control heads, and future compatible models that expose a USB network profile:

1. Plug the radio into the phone with a USB OTG cable. The app requests USB permission the first time.
2. KFDMobile brings up a private RNDIS-style network to the radio, negotiates an IP address by DHCP, and switches into DLI mode automatically.
3. Green banner: `USB DLI ready, radio at <address>`. The app is now ready to keyload.

While a USB radio is connected, the app remembers your previous Bluetooth PAN DLI configuration and restores it when you unplug the radio.

---

## MR emulator

The MR Emulator turns your phone into a radio that a KVL3000+ or KVL5000 can push keys to. From the Container tab, open an empty container and tap `MR Emulator`. Connect a KFD adapter to the phone and connect the KVL to the KFD adapter. Keys sent by the KVL appear in the container as they arrive, and you can save them to an encrypted `.ekc` file with a password.

---

## Tested devices

Radios that have been specifically tested with KFDMobile are listed below, along with any notes or firmware restrictions. Format follows the KFDtool desktop project's compatibility matrix so entries can be cross-referenced.

If you have used KFDMobile with a radio that is not listed, or with a status different from what is shown here, please open an issue so this table can be updated.

| Product line                 | Support status   | KFDMobile version | KFD firmware | Radio firmware          | Notes            |
|------------------------------|------------------|-------------------|--------------|-------------------------|------------------|
| Bendix King BK               | Untested         | none              | none         | none                    |                  |
| Bendix King KNG              | Untested         | none              | none         | none                    |                  |
| EF Johnson VP/VM 8000        | Untested         | none              | none         | none                    |                  |
| EF Johnson VP/VM x000        | Untested         | none              | none         | none                    |                  |
| EF Johnson VP/VM x00         | Untested         | none              | none         | none                    |                  |
| EF Johnson 5100/ES           | Untested         | none              | none         | none                    |                  |
| Harris XL                    | Untested         | none              | none         | none                    |                  |
| Harris Unity (XG-100)        | Untested         | none              | none         | none                    | [^ch100]         |
| Harris XG                    | Untested         | none              | none         | none                    | [^harrisxg]      |
| Kenwood NX                   | Untested         | none              | none         | none                    | [^kenwood]       |
| Kenwood TK                   | Untested         | none              | none         | none                    | [^kenwood]       |
| Motorola APX (TWI)           | Tested, passes   | >= 0.6.2          | Any          | Any                     |                  |
| Motorola APX (DLI, BT PAN)   | Tested, passes   | >= 0.6.2          | none         | >= R27.03               | [^freon][^bipan] |
| Motorola APX (DLI, USB)      | Tested, passes   | >= 0.6.2          | none         | >= R27.03               | [^freon][^usbdli]|
| Motorola ASTRO 25 (MACE)     | Untested         | none              | none         | none                    |                  |
| Motorola ASTRO 25 (UCM)      | Tested, passes   | >= 0.6.2          | Any          | Any                     |                  |
| Motorola ASTRO               | Untested         | none              | none         | none                    |                  |
| Tait TP/TM 9x00 series       | Untested         | none              | none         | none                    | [^kiwikey]       |
| Thales Liberty               | Untested         | none              | none         | none                    |                  |

[^ch100]: XG-100M supports native TWI keyloading only via CH100 (CH721 supports TWI keyloading via Harris adapter cable 14002-0143-10).
[^harrisxg]: Requires Harris adapter cable (portable: unknown part number; mobile: 14002-0143-10).
[^kenwood]: Requires SCM hardware module.
[^freon]: "Freon" (OMAP L-138) architecture only (APX 900, 6000 BN, 8000, 8500, NEXT, N-series, and BN mobiles). Non-Freon APX platforms are flagged by the in-app compatibility check.
[^bipan]: Radio must have Bluetooth Data / DLI enabled in its codeplug and be paired to the phone. Some phones require Airplane Mode with Bluetooth on for routing to reach the PAN interface.
[^usbdli]: Radio must have the USB radio-network profile enabled in its codeplug. KFDMobile brings up the RNDIS network and DHCP client automatically when the radio is plugged in.
[^kiwikey]: Requires [kiwi**key**](https://github.com/beepbooplabsltd/kiwikey) keyloading adapter.

### MR emulator keyloader support

The MR Emulator lets a Key Variable Loader push keys directly into KFDMobile so they can be saved into an encrypted container. Keyloaders that have been tested pushing keys to KFDMobile:

| Keyloader           | Support status | KFDMobile version | Notes           |
|---------------------|----------------|-------------------|-----------------|
| Motorola KVL3000+   | Tested, passes | Any               |                 |
| Motorola KVL5000    | Tested, passes | >= 0.5.3          | [^kvl5000rsi]   |
| Motorola KVL4000    | Untested       | none              |                 |
| Motorola KVL3000    | Untested       | none              |                 |
| Third-party         | Untested       | none              |                 |

[^kvl5000rsi]: The KVL5000 issues an RSI-items inventory query before it will keyload; KFDMobile responds with the factory-default RSI item that the KVL5000 requires. Older versions returned an unsupported response and the KVL5000 would abort before sending keys.

---

## Building

Clone the repository:

```
git clone https://github.com/majmongoose/KFDMobile.git
```

Open the project in Android Studio, or build a debug APK from the command line:

```
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/KFDMobile-v<version>-debug.apk`. Transfer it to your Android device and install it (you will need to allow installation from an unknown source in Android settings).

Package name: `com.sentinelcomms.kfdmobile`.

---

## License

KFDMobile is licensed under the MIT License. See the [LICENSE](LICENSE) file for the full text.

It is a derivative work of KFDtool by KFDtool LLC, Natalie Moore, and Ilya Smirnov, licensed under the MIT License. Source: https://github.com/omahacommsys/KFDtool/tree/master/sw/control

```
Copyright 2019-2020 KFDtool, LLC
Copyright 2021-2023 Natalie Moore
Copyright 2023 Ilya Smirnov
Copyright 2026 Charles Bricker (KFDMobile Android port and additions)
```
