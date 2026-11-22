# Bridge — Design System

Bridge should feel like a small utility Apple shipped: a menu bar popover on the Mac, a quiet companion app on Android. Reference: the Vorssaint menu bar panel (tab strip, small-caps section headers, soft filled cards, system-blue accent, Settings / Quit footer). Inspiration only — no Vorssaint code or assets (it is GPL; Bridge is MIT).

Personal build, not distributed: SF Pro is used on both platforms by owner decision.

## Principles

1. **System first.** Follow the OS light/dark appearance. On the Mac use semantic colors only (`.primary`, `.secondary`, `.tertiary`, `Color.accentColor`, `Color(nsColor: .separatorColor)`), never hex.
2. **Monochrome, one accent.** Surfaces, text and icons are black/white/gray. The only color is the system accent (selected tab, toggles, primary button, progress) plus status color used strictly for meaning: green = connected, red = error/destructive, orange = warning/pairing in progress. Nothing decorative is colored.
3. **Glass is the control layer, not the content layer.** Liquid Glass (`glassEffect`, macOS 26+) goes on the tab strip and footer buttons only. Content cards are flat soft fills. Never glass on glass.
4. **Quiet hierarchy.** Weight and size do the work. No gradients, no shadows on cards, no eyebrow labels above headings, no colored side borders.

## Brand

- Name: **Bridge** (title case in UI).
- Mark: the **Link** glyph — `design/logo-concepts/link.svg` (two brackets joined by a bar). Monochrome only.
  - Mac menu bar: template image from the glyph, 18×18 pt (with @2x).
  - Popover header: glyph, 26 pt tall, `.primary`, centered (as Vorssaint centers its logo).
  - Mac app icon: update `Resources/Bridge.icon` (Icon Composer): light = white fill + black glyph; dark = near-black fill + white glyph; tinted = glyph. Regenerate `Bridge.icns` (white squircle, black glyph) for the fallback.
  - Android launcher: adaptive icon, white background + black glyph, plus a `monochrome` layer (themed icons, Android 13+). Notification small icon: white glyph on transparent.
  - In-app (Android hero, pairing screen): glyph in `label` color.

## Color tokens

Mac resolves these through semantic SwiftUI/AppKit colors. Android defines them in `values/colors.xml` + `values-night/colors.xml` with these exact values (Apple system colors).

| Token | Light | Dark | Mac equivalent |
|---|---|---|---|
| background (Android screen) | #F2F2F7 | #000000 | popover material (system) |
| card | #FFFFFF | #1C1C1E | `Color.primary.opacity(0.06)` |
| cardPressed / fill | #E5E5EA | #2C2C2E | `Color.primary.opacity(0.10)` |
| label | #000000 | #FFFFFF | `.primary` |
| secondaryLabel | #993C3C43 | #99EBEBF5 | `.secondary` |
| tertiaryLabel | #4D3C3C43 | #4DEBEBF5 | `.tertiary` |
| separator | #4A3C3C43 | #99545458 | `Color(nsColor: .separatorColor)` |
| accent | #007AFF | #0A84FF | `Color.accentColor` |
| accentSoft (selected tab bg) | accent @ 13% | accent @ 20% | same |
| green | #34C759 | #30D158 | `Color(nsColor: .systemGreen)` |
| red | #FF3B30 | #FF453A | `Color(nsColor: .systemRed)` |
| orange | #FF9500 | #FF9F0A | `Color(nsColor: .systemOrange)` |

QR codes are always black on a white card in both appearances (scanability).

## Typography — SF Pro everywhere

Mac: `Font.system(...)` (that *is* SF Pro); codes and IPs use `.monospaced` design (SF Mono). Remove Plus Jakarta Sans / DM Mono and the font registration code.
Android: bundle SF Pro instances generated from the macOS system font (`/System/Library/Fonts/SFNS.ttf`, opsz + wght axes) — Text optical size for ≤19 sp, Display optical size for ≥20 sp — plus SF Mono from `SFNSMono.ttf` for codes. Weights: Regular 400, Medium 500, Semibold 600, Bold 700.

| Role | Mac (pt) | Android (sp) | Weight |
|---|---|---|---|
| Large title (Android tab title) | — | 34, tracking +0.01 | Bold (Display) |
| Title (screen/hero) | 15 | 22 | Semibold (Display on Android) |
| Headline (row/card title) | 13 | 17 | Semibold |
| Body | 12 | 17 | Regular |
| Subhead (secondary row text) | 11 | 15 | Regular, secondaryLabel |
| Section header | 11, uppercase, tracking 0.6 | 13, uppercase, tracking 0.04em | Semibold, secondaryLabel |
| Caption / meta | 10 | 12 | Regular, secondaryLabel |
| Code (pair code, IP) | 22 SF Mono Medium | 28 SF Mono Medium | Medium |

Android sizes are `sp` so they follow the system font scale.

## Shape & spacing

- Corner radii (continuous on Mac): card 10, tab strip container 12, selected tab 8, buttons 8 (Mac) / 12 (Android, 50 dp tall primary), small chips 6, Android grouped list cards 12, Android floating tab bar = capsule.
- Spacing scale: 4, 8, 12, 16, 20, 24. Mac popover content inset 14; card padding 12; gap between cards 8. Android screen side margin 16; grouped card inner row padding 16 horizontal; row min height 52 dp (≥48 dp touch).
- Separators: hairline (0.5 pt / 1 px), inset to the text start inside cards.
- Icons: Mac uses SF Symbols (regular weight, 15 pt in tabs, 14 pt in rows). Android uses the same SF Symbols rendered to tintable PNGs per density (`drawable-*dpi/sf_*.png`), tinted with color tokens.

## Mac — menu bar popover (width 360)

Top to bottom, like Vorssaint:

1. **Header** — centered Link glyph. To its right, aligned trailing, a compact status capsule: green dot + "Connected · Wi-Fi/Bluetooth/Hotspot", orange dot "Waiting for phone", gray "Not paired". 11 pt.
2. **Tab strip** — icon-only, equal-width buttons inside a 12-radius container (`glassEffect` on macOS 26+, else `Color.primary.opacity(0.04)` fill + separator stroke). Tabs: Devices (`laptopcomputer.and.iphone`), Notifications (`bell`), Transfers (`arrow.up.arrow.down`). Selected: accent icon on accentSoft 8-radius pill; others `.secondary`. Tooltips with the tab name. Badge count (small accent capsule) on Notifications/Transfers when non-zero.
3. **Section header row** — uppercase section title (e.g. DEVICES) leading, optional count or action trailing (e.g. "Clear all" as a plain accent text button).
4. **Content** — cards (`Color.primary.opacity(0.06)`, radius 10). Row anatomy: leading 28 pt icon tile (SF Symbol in `.secondary`, or the phone app icon for notifications, radius 6), title 13 semibold, subtitle 11 secondary, trailing control (toggle / chevron / button). Groups of related settings share one card with inset hairline separators.
5. **Footer** — two equal buttons, 8-radius, glass on macOS 26+ (fallback primary 0.06 fill): `gearshape` Settings, `power` Quit. Settings replaces the content with the settings sections (tab strip shows no selection; a back chevron + "Settings" title appears in the section header row).

Screens:
- **Not paired** — header, then a card with the QR code (white card, 168 pt), the pairing code in SF Mono below, "Scan with Bridge on your Android phone" subtitle, and a disclosure row "Other ways to connect" (IP/port, Bluetooth ID). No tab strip until paired.
- **Devices** — this Mac + the phone as rows (name, transport, IP masked behind the existing reveal toggle), transport status row (Wi-Fi / Bluetooth / Hotspot with SF Symbols `wifi`, `antenna.radiowaves.left.and.right`, `personalhotspot`), fast-link status line when active, "Pair another device" plain row, Unpair as a red plain text button at the bottom of the card group.
- **Notifications** — each notification is a card: app icon (from the phone) 28 pt radius 6, app name 11 secondary + relative time trailing, title 13 semibold, body 12 (3 lines), actions as small bordered buttons (8-radius, primary 0.08 fill), inline reply field. Empty state: centered `bell.slash` 28 pt tertiary + "No notifications" + one line of secondary text.
- **Transfers** — drop zone card (dashed 1 pt separator stroke, `arrow.down.doc` icon, "Drop files to send to your phone"; highlights with accentSoft while dragging), then transfer cards: file-type SF Symbol tile, name, size · speed · ETA, thin accent progress bar (3 pt, capsule, track primary 0.08), pause/cancel icon buttons; completed shows checkmark (green) + "Show in Finder"; failed shows red text.
- **Settings** — sections: NOTIFICATIONS (master + sub-toggles), TRANSFERS (auto-accept, fast transfers via hotspot, save location row), PERMISSIONS (Accessibility, Notifications, Location rows with status + "Open Settings"), ABOUT (version, licenses). Toggles: native `Toggle(.switch)` with `.controlSize(.small)` tinted accent.

Popover chrome: remove the forced `darkAqua` appearance and the custom window background color — use the system popover material so light/dark and Liquid Glass come from macOS.

Motion: tab content cross-fades 0.15 s ease-out; status dot breathes only while connecting; respect Reduce Motion (no breathing).

## Android — "an Apple app, on Android"

Apple visual language, Android mechanics: edge-to-edge with proper insets, system Back always works, 48 dp touch targets, sp text, system dialogs/sheets/share sheet.

- **Structure** — three tabs: Home, Files, Settings. Each screen: large title (34 sp Bold) at top-left under the status bar, content in **grouped inset cards** (iOS Settings style) on `background`.
- **Floating glass tab bar** — capsule, centered, 16 dp from the bottom inset, height 62 dp, three items (SF Symbol + 10 sp label). Selected item: accent icon+label on an accentSoft capsule; others secondaryLabel. Glass: on API 31+ a real backdrop blur of the content behind (RenderNode + `RenderEffect.createBlurEffect`, radius ~24 dp) under a translucent card tint (light #B3FFFFFF / dark #B31C1C1E) + 0.5 dp hairline stroke (light #1A000000 / dark #26FFFFFF); below API 31 the tint at 96% opacity. Content scrolls under it (bottom padding = bar height + inset).
- **Home** — hero card: Link glyph (label color), "Connected to <Mac name>" (22 sp Semibold) or "Connecting…", transport line with SF Symbol (Wi-Fi / Bluetooth / Hotspot) in secondaryLabel, green/orange/gray status dot. Below: DEVICES group (This phone, Your Mac rows), troubleshooting group only on failure, primary button when offline.
- **Files** — "Send files to Mac" row-card with `square.and.arrow.up` (accent) and chevron; TRANSFERS group with rows: file SF Symbol tile, name, meta, thin accent progress bar; empty state like the Mac.
- **Settings** — grouped sections mirroring today's content: SYNCED (Notifications master + sub-options, Auto-accept files, Fast transfers), THIS PHONE (Vibrate, Save location, Notification access, Accessibility), DEVICE (Unpair in red). Rows: title 17 sp, optional subtitle 13 sp secondary, trailing switch or chevron; hairline separators inset 16 dp.
- **Switches** — Apple-style: 51×31 dp capsule track, 27 dp white thumb with soft shadow, on = accent, off = #E9E9EA light / #39393D dark.
- **Pairing (not paired)** — centered Link glyph (64 dp), large title "Connect your Mac", secondary line, primary capsule-cornered (12 dp radius) accent button "Scan QR Code" with `qrcode.viewfinder`, divider "or enter the code", six code boxes (SF Mono 28 sp, card fill, 12 dp radius, accent 1.5 dp stroke on the active box), "Connect manually" plain accent text link.
- **Banners** (permissions, errors) — card with SF Symbol in status color, title + subtitle, trailing plain accent action. No colored card backgrounds.
- System bars: transparent, light/dark icons following theme.

## States (both platforms)

Hover (Mac): rows/buttons get `Color.primary.opacity(0.05)` extra fill. Pressed: 0.10. Disabled: 40% opacity. Focus: system focus ring. Loading: native small spinner. Errors: red text + recovery action. Empty: icon + one-line title + one-line hint.
