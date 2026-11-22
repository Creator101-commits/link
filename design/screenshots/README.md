# Design screenshots

Visual reference captures for the Bridge rebrand (see `../DESIGN.md`). Mac shots render `BridgeMenuView` via `mac-app/scripts/snapshot/main.swift` (`make snapshots` in `mac-app/`); Android shots come from the debug demo mode (`demo_state` intent extra) on a Pixel 8 API 34 emulator.

## macOS (menu-bar popover, 360pt wide)

- `mac-not-paired-light.png` / `mac-not-paired-dark.png` — pairing screen: QR code, pairing code, manual IP entry
- `mac-devices-light.png` / `mac-devices-dark.png` — connected devices list with paired phone entries
- `mac-notifications-light.png` / `mac-notifications-dark.png` — notification list with app icons and action/reply buttons
- `mac-transfers-light.png` / `mac-transfers-dark.png` — transfer list (active ~40%, completed, failed) plus drop zone
- `mac-settings-light.png` / `mac-settings-dark.png` — settings screen
- `mac-call-ringing-light.png` / `mac-call-ringing-dark.png` — incoming call card pinned above the tab strip (Mom, Answer/Decline) with phone status
- `mac-apps-settings-light.png` / `mac-apps-settings-dark.png` — Settings APPS section: per-app notification level pickers

Note: captured via `cacheDisplay` fallback (Screen Recording permission not granted to the capture process), so popover vibrancy/materials render as the window background color rather than live material.

## Android (Pixel 8, API 34)

- `android-pairing-light.png` / `android-pairing-dark.png` — unpaired pairing screen
- `android-home-light.png` / `android-home-dark.png` — connected home tab
- `android-files-light.png` / `android-files-dark.png` — files tab with active/completed/failed sample transfers
- `android-files-empty-light.png` / `android-files-empty-dark.png` — files tab empty state
- `android-settings-light.png` / `android-settings-dark.png` — settings tab
