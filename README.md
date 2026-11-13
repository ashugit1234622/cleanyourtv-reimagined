<div align="center">

<img src="cleanyourtv-reimagined-banner.svg" alt="CleanYourTV Reimagined" width="100%">

### Visual Showcase

**See it running on a real TV.** Every screenshot and recording below was captured directly from a Xiaomi Smart TV 4K, not a mockup and not an emulator.

![Device](https://img.shields.io/badge/Device-Xiaomi%20Smart%20TV%204K-1f6feb?style=for-the-badge)
![OS](https://img.shields.io/badge/Android%20TV-14-3ddc84?style=for-the-badge)
![Screenshots](https://img.shields.io/badge/Screenshots-4K%20PNG-8b5cf6?style=for-the-badge)
![Recordings](https://img.shields.io/badge/Recordings-1080p%20MP4-f97316?style=for-the-badge)

</div>

---

## About this fork

**CleanYourTV Reimagined** is a personal, customized fork of the CleanYourTV launcher by Gathin. It keeps the lightweight spirit of the original and adds iOS-style motion, fluid focus, tvOS-style Recents, a smart boot intro, and a phone-based remote.

> This is an unofficial fork. It isn't affiliated with or endorsed by Gathin or the original CleanYourTV project. The banner above is original artwork made for this fork and doesn't reuse the original logo. Please see the upstream project's license for the terms that apply to the code.

**Captured on:** Xiaomi Smart TV 4K (`MiTV_MZTU0`, Android 14)

---

## Motion in action

Three short 1080p recordings show the part screenshots can't: how it moves.

| Recording | Length / Size | What you'll see |
|---|---|---|
| **[`anim_open_and_close_app.mp4`](anim_open_and_close_app.mp4)** | ~8s (8.45 MB) | **iOS-style open and close.** The focused YouTube tile gives a tactile press dip, then expands into a fullscreen curtain. Press Home and the window shrinks smoothly back into the dock tile it came from. |
| **[`anim_glide_focus.mp4`](anim_glide_focus.mp4)** | ~9s (4.13 MB) | **Gliding focus.** One morphing pill travels across the top capsules (`Inputs` → `Wi-Fi` → `Reorder` → `Settings`), then Quick Settings opens and a vertical focus bar glides between options. |
| **[`anim_intro_reveal.mp4`](anim_intro_reveal.mp4)** | ~6s (162 KB) | **Boot and wake intro.** The wordmark enters, the greeting holds, and a circular aperture opens into the Home screen. |

---

## Screenshots

Captured in 4K. Click any preview to open the full-resolution image.

| Preview | Feature |
|:---:|---|
| <a href="01_home_screen.png"><img src="01_home_screen.png" width="380" alt="Home screen"></a> | **Home**<br>A clean, Apple TV-inspired Home with a bird wallpaper, live Lonavala weather (`26°C SUNNY`), time and date, a unified status capsule (`RAM 67%`, `Storage 28%`, `Remote Battery 87%`, connected `CMF Buds`), and a custom dock.<br><sub>[`01_home_screen.png`](01_home_screen.png)</sub> |
| <a href="02_unified_boost_card.png"><img src="02_unified_boost_card.png" width="380" alt="Unified boost card"></a> | **Boost card**<br>The System & Accessories popup, with live RAM and Storage progress bars, a `Memory & Storage Boost` button, a battery monitor, and a shortcut to Bluetooth settings.<br><sub>[`02_unified_boost_card.png`](02_unified_boost_card.png)</sub> |
| <a href="03_quick_settings_modes.png"><img src="03_quick_settings_modes.png" width="380" alt="Quick Settings with live modes"></a> | **Quick Settings**<br>A side panel that reads the TV's real state: Picture mode (`Standard`), Audio mode (`DTS: Clarity + Virtualizer`), Network, and the connected Bluetooth audio device (`CMF Buds`).<br><sub>[`03_quick_settings_modes.png`](03_quick_settings_modes.png)</sub> |
| <a href="04_recent_apps_menu.png"><img src="04_recent_apps_menu.png" width="380" alt="Recent apps menu"></a> | **Recents**<br>A tvOS-style Recent Apps menu, opened with a long press on Home. It lists running user apps (TizenTube, Cx File Explorer, CPU Info) with per-app RAM badges, a total memory pill (`Free: 541 MB / 1.7 GB`), and `Close App` / `Close All` actions.<br><sub>[`04_recent_apps_menu.png`](04_recent_apps_menu.png)</sub> |
| <a href="05_app_launch_settings.png"><img src="05_app_launch_settings.png" width="380" alt="App launch animation settings"></a> | **Animation settings**<br>Tune the iOS-style launch animation: speed, easing curve, background depth, card artwork morph, and a live preview.<br><sub>[`05_app_launch_settings.png`](05_app_launch_settings.png)</sub> |
| <a href="06_intro_settings.png"><img src="06_intro_settings.png" width="380" alt="Intro settings"></a> | **Intro settings**<br>Customize the boot and wake intro: wake gap threshold, hold duration (Auto or timed), animation style presets, and message modes.<br><sub>[`06_intro_settings.png`](06_intro_settings.png)</sub> |
| <a href="07_phone_bridge_pairing.png"><img src="07_phone_bridge_pairing.png" width="380" alt="Phone Bridge pairing"></a> | **Phone Bridge**<br>Pair your phone with the TV. The screen shows the endpoint URL (`http://192.168.1.37:8765`), a 5-minute security PIN, and a live QR code to scan.<br><sub>[`07_phone_bridge_pairing.png`](07_phone_bridge_pairing.png)</sub> |
| <a href="08_translucent_inputs_menu.png"><img src="08_translucent_inputs_menu.png" width="380" alt="Translucent inputs menu"></a> | **Inputs**<br>A frosted, translucent inputs popup with a morphing pill highlight over `Ashu's laptop · HDMI 1`, HDMI 2, HDMI 3, and AV.<br><sub>[`08_translucent_inputs_menu.png`](08_translucent_inputs_menu.png)</sub> |

---

## Good to know

- **Real hardware only.** All captures come from the live TV. Screenshots are pulled over ADB with `adb exec-out screencap -p`.
- **Built for one TV, tuned on it.** The Xiaomi-specific features (live picture and sound modes) depend on Xiaomi's TV middleware. Other Android TVs may work, but those parts will fall back quietly.
- **Same folder, same names.** The file names above are referenced directly, so keep the images and videos next to this README.

---

<div align="center">

**CleanYourTV Reimagined** · a personal fork built on CleanYourTV by Gathin

<sub>Unofficial and not affiliated with the original project. Check the upstream license before reusing or redistributing anything.</sub>

</div>
