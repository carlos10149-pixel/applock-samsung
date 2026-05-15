# AppLock for Samsung One UI

> **The most Samsung-native AppLock experience on Android.**  
> Biometric protection for your apps with real-time notification privacy — built specifically for One UI devices.

<p align="center">
  <img src="docs/screenshot_lock.png" width="220"/>
  &nbsp;&nbsp;
  <img src="docs/screenshot_main.png" width="220"/>
</p>

---

## ✨ Features

- 🔒 **Biometric Lock** — Fingerprint & PIN protection for any installed app
- 🔔 **Notification Privacy** — Hides message content from WhatsApp, Instagram, TikTok, Facebook Messenger in the notification tray
- 🖼️ **Recents Shield** — Covers locked app thumbnails in the Samsung task switcher
- ⚡ **Instant Detection** — Dual-engine (AccessibilityService + UsageStats) for near-zero-latency locking
- 🧩 **Quick Settings Tile** — Toggle AppLock on/off from the notification shade
- 🛡️ **Anti-Uninstall** — Device Admin protection prevents unauthorized removal
- 🌙 **Dark / Light / System** themes
- 🔋 **Battery Optimized** — Smart debouncing and coroutine-based engine

---

## 📱 Compatibility

| Device | Android | Status |
|--------|---------|--------|
| Samsung Galaxy (One UI 4, 5, 6) | Android 12 – 14 | ✅ Fully supported |
| Other Android devices | Android 10+ | ⚠️ May work, not tested |

> Built and optimized exclusively for **Samsung One UI**. Extensive work has gone into handling Samsung-specific behaviors: edge panels, keyboard overlays, Game Launcher, Bixby, DeX, and task switcher animations.

---

## 🚀 Getting Started

### Option A — Install the APK (Easiest)
1. Download the latest APK from the [Releases](../../releases) page
2. On your Samsung device: **Settings → Apps → Special access → Install unknown apps** → enable for your browser/file manager
3. Open the APK and install
4. Grant all requested permissions (Accessibility, Notification Access, Draw over apps)

### Option B — Build from source
```bash
git clone https://github.com/YOUR_USERNAME/applock-samsung.git
cd applock-samsung
./gradlew assembleRelease
```

> **Note:** You'll need to create your own signing keystore or use debug build (`assembleDebug`) for testing.

---

## 🔧 Required Permissions

| Permission | Why |
|-----------|-----|
| Accessibility Service | Detects which app is in the foreground |
| Notification Access | Hides sensitive notification content |
| Draw Over Other Apps | Shows lock screen overlay instantly |
| Usage Stats Access | Secondary detection layer for Samsung |
| Device Admin | Anti-uninstall protection (optional) |
| Ignore Battery Optimizations | Keeps service alive on Samsung's aggressive power management |

---

## 🏗️ Architecture

```
app/
├── services/
│   ├── AppLockAccessibilityService.kt   # Core lock engine (dual-mode detection)
│   ├── NotificationGuardService.kt      # Notification content privacy
│   ├── FastShieldManager.kt             # Instant overlay (blocks screenshots in recents)
│   ├── AppLockTileService.kt            # Quick Settings tile
│   └── BootReceiver.kt                  # Auto-start on reboot
├── LockOverlayActivity.kt               # Biometric prompt UI
├── MainActivity.kt                      # App list & settings UI
└── data/
    ├── AppLockDatabase.kt               # Room DB for locked apps list
    └── PrefsManager.kt                  # Settings & cache
```

### Key Design Decisions

- **Dual-engine detection**: AccessibilityService for real-time events + UsageStats polling every 250ms as a fallback (Samsung can delay or drop accessibility events)
- **Smart debounce**: Uses event timestamps (not wall clock) to distinguish real app-opens from Samsung's trailing animation events
- **Samsung SKIP_PACKAGES**: 40+ Samsung system packages explicitly excluded to prevent false locks from Bixby, Game Launcher, Edge panels, etc.
- **`lockPending` auto-expire**: 3-second watchdog resets stuck lock state if the overlay is killed mid-launch (common when navigating quickly on 120Hz)

---

## 🐛 Known Issues / Limitations

- Notification hiding requires granting **Notification Access** manually in Settings
- First launch requires walking through ~4 permission screens (Samsung restriction)
- Some Samsung-specific system apps (Secure Folder, Knox) cannot be locked by design

---

## 🤝 Contributing

Pull requests are welcome! Please test on a real Samsung One UI device before submitting. If you find a Samsung-specific bug, include your **One UI version** and **device model** in the issue.

---

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.

---

*Made with ❤️ and way too much Samsung debugging.*
