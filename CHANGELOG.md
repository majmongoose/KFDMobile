# Changelog

All notable changes to KFDDroid are documented here.

KFDDroid is an Android port of [KFDtool](https://github.com/omahacommsys/KFDtool/tree/master/sw/control)
by KFDtool LLC, Natalie Moore, and Ilya Smirnov, licensed under the MIT License.

---

## [0.6.5] - 2026-11-16

### New
- Updated the app's Android support libraries to their current versions.

### Fixed
- KFDMobile no longer shows up in the "Open with" list for every file on your phone. It now only offers to open key container (.ekc) files, plus unnamed attachments from email and messaging apps.
- Numbers and hex values now display the same way regardless of the phone's language setting.

---

## [0.6.4] - 2026-11-04

### New
- The app is much smaller: the installed download is roughly 70 percent smaller than before.

### Fixed
- Keys loaded through a KFD adapter are no longer written to the phone's system log.
- The app writes far less to the system log in general. Routine connection chatter is only recorded in developer builds; normal builds log only real errors and a few key events.

---

## [0.6.3] - 2026-11-04

### Fixed
- The MR Emulator no longer writes received key material to the phone's system log. Only a short summary of each transfer (number of keys, keyset, and algorithm) is recorded.

---

## [0.6.2] - 2026-09-23

### Fixed
- On Android 15 and Android 16, the top of the app (the "KFDMobile v0.x.x" bar) was rendering underneath the phone's clock and status icons. The toolbar now sits below the status bar the way it should, and the bottom navigation bar also respects the gesture indicator area.

---

## [0.6.1] - 2026-09-23

Internal Play Store compliance update.

### Changed
- The app now targets Android 16 (API level 36) as required by Google Play for new uploads. No user-visible behavior changes.

---

## [0.6.0] - 2026-09-22

**Important:** The app has been renamed. This release is a fresh installation; existing installs must be uninstalled first before installing 0.6.0.

### New
- The app is now called **KFDMobile**, both on your home screen and in the Play Store listing.
- MR Emulator status messages have been simplified. Only three states are shown: waiting for a KFD, connected and receiving keys, and adapter error. The technical protocol chatter that used to scroll through the status line is now only in logs.

### Fixed
- Opening a stored key in the Add/Edit Key screen no longer silently truncates the key. Previously, any stored key (regardless of algorithm) could be shortened to 10 characters if you opened it to look at it or edit it. Affected AES, DES, and proprietary/unknown algorithms alike.
- Proprietary or unknown-algorithm keys longer than an AES-256 key can now be entered, generated, and stored without being clipped.
- The DES-key-truncated-on-tab-switch bug on the Keyload screen has been fully closed. Previous fix worked the first time you left and came back; this one also survives repeated tab switches.
- The MR Emulator popup no longer auto-closes when the KVL disconnects mid-session. It stays open until you tap Cancel, so a KVL that reconnects to send more keys is handled without you having to reopen the dialog.

---

## [0.5.3a] - 2026-07-13

### Fixed
- Rotating the phone with a key container open no longer closes the container. The open container, its file location, password, and any selected keys survive rotation intact. Also fixes a related issue where the same state could be lost after rotation if you navigated to a different tab and came back.

---

## [0.5.3] - 2026-07-13

### New
- Motorola KVL5000 is now supported in the MR Emulator. Previously the KVL5000 would query the app for something the KVL3000+ never asked about, and the app would refuse the query; the KVL5000 then gave up before sending any keys. The app now answers that query the way a factory-fresh Motorola radio does.
- The MR Emulator session now stays open across the KVL's normal connect/disconnect cycles. Older versions would end the session as soon as the KVL disconnected, which was noisy and could miss follow-up keyloads. Now only the Cancel button in the MR Emulator dialog stops emulation.

### Fixed
- On the Keyload screen, if you had DES selected with a valid key entered, then switched to another tab and came back, the key was silently truncated and marked invalid. It now survives the tab switch intact.
- The green "connected" banner on the Connect tab now shows the KFD adapter type (KFDShield / Original KFDTool) instead of the generic USB device name.
- On the Radio Keys screen, SLN/CKR validation for Erase Key is now inline red under the field as you type, matching the rest of the app. Previously it either did nothing until you tapped a different field or popped up an out-of-place status message.
- The MR Emulator popup's "Keys received" counter no longer uses an oversized bold font; it now matches the rest of the dialog text.

---

## [0.5.2] - 2026-07-12

**Important:** This release changes the app's internal identity. You must uninstall any prior version before installing 0.5.2; Android treats it as a different app.

### New
- DES key parity is now checked when you enter a DES-OFB or DES-XL key. Randomly generated DES keys have their parity fixed automatically so they pass validation.
- The key field on the Keyload screen and the container Add/Edit Key screen is now capped at the number of hex characters required by the selected algorithm; no more accidentally typing past the end.
- The DLI Configuration dialog now scrolls when the on-screen keyboard is covering part of it, and tapping the keyboard's "Done" key saves the configuration.
- Generate Key now asks before overwriting a key you've already entered.
- If you plug in a radio the app can't work with, you now get a clear "Unsupported radio" popup instead of the app hanging trying to talk to it.

### Fixed
- The Load button on the Keyload screen now enables the moment you finish typing a valid key; no need to tap another field first to make it wake up.
- The red "invalid key" indicator no longer nags you while you're still typing a short-but-valid-looking key. It only appears once the key is the right length (or too long), or when you tap away from the field.
- The DLI settings you had configured before plugging in a USB radio are preserved and automatically restored when the USB radio is unplugged.
- The DLI mode button now reads simply "DLI" instead of "DLI over IP".

---

## [0.5.1] - 2026-07-12

### New
- **APX mobile control heads are now recognized over USB**: plug in a control head the same way you would a portable radio and the app connects.
- **Automatic recognition of future APX models**: instead of matching a fixed list of specific radios, the app now identifies any Motorola APX-family USB device by its manufacturer signature. When new models are released they should work without needing an app update.

---

## [0.5.0] - 2026-07-12

### New
- **Radio-over-USB support**: plug an APX radio directly into the phone's USB port and the app now talks to it as if it were on Bluetooth. No cradle, no PC in the middle. The app finds the radio automatically, gets an address, and switches itself into DLI mode when it's ready.
- **Radio compatibility check**: when you test the DLI connection, the app now asks the radio what model it is. If the radio can't do software keyloading (AN/Mackinaw/OMAP), you get a clear pop-up telling you to use a KFD adapter instead, and software keyload is disabled to prevent a confusing failure later. Compatible (Freon) radios are noted in the log and everything works normally.
- **Helpful note in the DLI configuration**: reminds you that internet access must be enabled on the Bluetooth connection to the radio, and that some phones need Airplane Mode with Bluetooth on for routing to work.

### Fixed
- **Container tab crash on Samsung Fold and other wider-screen phones**: the wider-screen version of the container screen was missing pieces, which crashed the app the moment you tapped the tab. Fixed.
- **Keyload buttons now disable when the USB radio is unplugged**: previously the app would leave Load / Erase / View Key Info buttons enabled after a disconnect, and taps would just fail. They now grey out immediately.

---

## [0.3.1] - 2026-05-29

### Changed
- DLI section on Connect tab simplified: IP/port/variant fields moved into a **Configure** dialog; main screen now shows only **Configure** and **Test** buttons
- Configure dialog includes CPS hint: find the "Bluetooth PAN Network Base Address" in the Radio Wide page
- Show Network Interfaces button removed

---

## [0.3.0] - 2026-05-29

### Added
- **DLI over IP**: second connection mode on the Connect tab alongside KFD Adapter
  - Supports standard (MFID 0x00) and Motorola (MFID 0x90) session variants
  - Works over Bluetooth PAN and USB RNDIS (radio appears as a network device)
  - Default settings match KFDtool desktop: `192.168.132.1 : 49644, Motorola`
  - Mode and IP/port/variant settings persisted across app restarts
  - **Test** button: runs a full DLI session open/close with byte-level debug output
  - **Show Network Interfaces** button: lists active IPv4 interfaces to help identify the radio's BT PAN IP
- `DeviceProtocol` interface abstracts TWI and DLI transports; all key operations work over either mode without changes to the operation code
- `SessionControl` and `Mfid90SessionControlVer1` KMM classes for DLI session management
- `INTERNET` and `ACCESS_NETWORK_STATE` permissions added to manifest

### Fixed
- DLI UDP packets now correctly route via BT PAN / USB RNDIS when WiFi or LTE is also active; uses `ConnectivityManager.Network.bindSocket()` to force the correct interface (airplane mode workaround no longer needed)
- DLI configuration is automatically restored on app launch; no longer shows "Not configured" after a restart
- `ThreeWireProtocol.DeviceType` extracted to a standalone `DeviceType` enum shared by both transports

---

## [0.2.1] - 2026-05-29

### Added
- **Erase Key**: erase a specific key slot by SLN (Radio Keys tab)
- **Erase All Keys**: zeroize all keys in the radio (Radio Keys tab)
- **View Key Info**: list all active keys stored in the radio (keyset ID, SLN, algorithm ID, key ID)
- New **Radio Keys** tab (5th bottom-nav item) for all radio key management operations
- KMM classes: `ZeroizeCommand`, `ZeroizeResponse`, `InventoryCommandListActiveKeys`, `InventoryResponseListActiveKeys`, `KeyInfo`
- `ManualRekeyApplication`: `eraseKeys()`, `eraseAllKeys()`, `viewKeyInfo()` methods
- Operation logs now show only high-level status during progress; full protocol trace shown in an alert dialog on failure only
- MIT license header added to all Java source files

### Fixed
- Multi-keyload from containers was not storing keys; key batching now groups by `algorithmId + keysetId` matching `.NET KeyPartitioner` behavior
- `KeyItem` erase flag was bit 0 (wrong); corrected to bit 5 (0x20) matching `.NET`
- `KeyItem` KEK flag added (bit 7 / 0x80); was missing entirely
- `InventoryResponseListActiveKsetIds.toBytes()` was missing the 2-byte count field
- Receive queues flushed before each session to clear stale bytes from prior failed sessions
- APK output filename now includes version: `KFDDroid-v<version>-<buildType>.apk`
- Toolbar logo right-side padding restored

---

## [0.1.1] - 2026-05-29

### Added
- Version number displayed in toolbar subtitle

### Fixed
- Initial keyload fixes for single-key manual load
- Toolbar logo padding

---

## [0.1.0] - 2026-05-29

### Added
- Initial release
- USB OTG connection to KFD adapter (KFDtool / KFDShield)
- P25 Three-Wire Interface (TWI) protocol stack
- Manual keyload (single key: algorithm, keyset ID, key ID, SLN, key hex)
- Container keyload: open/create/edit/save `.ekc` encrypted key container files
- Multi-select keyload from container
- Device Info tab: reads adapter protocol version, firmware, hardware revision, model, serial number, unique ID
- Connect tab with USB connect/disconnect and protocol log
