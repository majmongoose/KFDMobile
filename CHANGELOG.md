# Changelog

All notable changes to KFDDroid are documented here.

KFDDroid is an Android port of [KFDtool](https://github.com/omahacommsys/KFDtool/tree/master/sw/control)
by KFDtool LLC, Natalie Moore, and Ilya Smirnov, licensed under the MIT License.

---

## [0.3.1] — 2026-05-29

### Changed
- DLI section on Connect tab simplified: IP/port/variant fields moved into a **Configure** dialog; main screen now shows only **Configure** and **Test** buttons
- Configure dialog includes CPS hint: find the "Bluetooth PAN Network Base Address" in the Radio Wide page
- Show Network Interfaces button removed

---

## [0.3.0] — 2026-05-29

### Added
- **DLI over IP** — second connection mode on the Connect tab alongside KFD Adapter
  - Supports standard (MFID 0x00) and Motorola (MFID 0x90) session variants
  - Works over Bluetooth PAN and USB RNDIS (radio appears as a network device)
  - Default settings match KFDtool desktop: `192.168.132.1 : 49644, Motorola`
  - Mode and IP/port/variant settings persisted across app restarts
  - **Test** button — runs a full DLI session open/close with byte-level debug output
  - **Show Network Interfaces** button — lists active IPv4 interfaces to help identify the radio's BT PAN IP
- `DeviceProtocol` interface abstracts TWI and DLI transports; all key operations work over either mode without changes to the operation code
- `SessionControl` and `Mfid90SessionControlVer1` KMM classes for DLI session management
- `INTERNET` and `ACCESS_NETWORK_STATE` permissions added to manifest

### Fixed
- DLI UDP packets now correctly route via BT PAN / USB RNDIS when WiFi or LTE is also active — uses `ConnectivityManager.Network.bindSocket()` to force the correct interface (airplane mode workaround no longer needed)
- DLI configuration is automatically restored on app launch; no longer shows "Not configured" after a restart
- `ThreeWireProtocol.DeviceType` extracted to a standalone `DeviceType` enum shared by both transports

---

## [0.2.1] — 2026-05-29

### Added
- **Erase Key** — erase a specific key slot by SLN (Radio Keys tab)
- **Erase All Keys** — zeroize all keys in the radio (Radio Keys tab)
- **View Key Info** — list all active keys stored in the radio (keyset ID, SLN, algorithm ID, key ID)
- New **Radio Keys** tab (5th bottom-nav item) for all radio key management operations
- KMM classes: `ZeroizeCommand`, `ZeroizeResponse`, `InventoryCommandListActiveKeys`, `InventoryResponseListActiveKeys`, `KeyInfo`
- `ManualRekeyApplication`: `eraseKeys()`, `eraseAllKeys()`, `viewKeyInfo()` methods
- Operation logs now show only high-level status during progress; full protocol trace shown in an alert dialog on failure only
- MIT license header added to all Java source files

### Fixed
- Multi-keyload from containers was not storing keys — key batching now groups by `algorithmId + keysetId` matching `.NET KeyPartitioner` behavior
- `KeyItem` erase flag was bit 0 (wrong); corrected to bit 5 (0x20) matching `.NET`
- `KeyItem` KEK flag added (bit 7 / 0x80); was missing entirely
- `InventoryResponseListActiveKsetIds.toBytes()` was missing the 2-byte count field
- Receive queues flushed before each session to clear stale bytes from prior failed sessions
- APK output filename now includes version: `KFDDroid-v<version>-<buildType>.apk`
- Toolbar logo right-side padding restored

---

## [0.1.1] — 2026-05-29

### Added
- Version number displayed in toolbar subtitle

### Fixed
- Initial keyload fixes for single-key manual load
- Toolbar logo padding

---

## [0.1.0] — 2026-05-29

### Added
- Initial release
- USB OTG connection to KFD adapter (KFDtool / KFDShield)
- P25 Three-Wire Interface (TWI) protocol stack
- Manual keyload (single key: algorithm, keyset ID, key ID, SLN, key hex)
- Container keyload — open/create/edit/save `.ekc` encrypted key container files
- Multi-select keyload from container
- Device Info tab — reads adapter protocol version, firmware, hardware revision, model, serial number, unique ID
- Connect tab with USB connect/disconnect and protocol log
