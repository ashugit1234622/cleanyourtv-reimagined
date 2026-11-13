<div align="center">

![CleanYourTV Reimagined](showcase/cleanyourtv-reimagined-banner.svg)

## Visual Showcase

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

Three short 1080p recordings show the part screenshots can't: how it moves. Click a file name to open and play it.

| Recording | Length / Size | What you'll see |
|---|---|---|
| ▶ **[`anim_open_and_close_app.mp4`](showcase/anim_open_and_close_app.mp4)** | ~8s (8.45 MB) | **iOS-style open and close.** The focused YouTube tile gives a tactile press dip, then expands into a fullscreen curtain. Press Home and the window shrinks smoothly back into the dock tile it came from. |
| ▶ **[`anim_glide_focus.mp4`](showcase/anim_glide_focus.mp4)** | ~9s (4.13 MB) | **Gliding focus.** One morphing pill travels across the top capsules (`Inputs` → `Wi-Fi` → `Reorder` → `Settings`), then Quick Settings opens and a vertical focus bar glides between options. |
| ▶ **[`anim_intro_reveal.mp4`](showcase/anim_intro_reveal.mp4)** | ~6s (162 KB) | **Boot and wake intro.** The wordmark enters, the greeting holds, and a circular aperture opens into the Home screen. |

---

## Screenshots

Captured in 4K. Click any image to open it at full resolution.

### 01 · Home

[![Home screen](showcase/01_home_screen.png)](showcase/01_home_screen.png)

A clean, Apple TV-inspired Home with a bird wallpaper, live Lonavala weather (`26°C SUNNY`), time and date, a unified status capsule (`RAM 67%`, `Storage 28%`, `Remote Battery 87%`, connected `CMF Buds`), and a custom dock.

### 02 · Boost card

[![Unified boost card](showcase/02_unified_boost_card.png)](showcase/02_unified_boost_card.png)

The System & Accessories popup, with live RAM and Storage progress bars, a `Memory & Storage Boost` button, a battery monitor, and a shortcut to Bluetooth settings.

### 03 · Quick Settings

[![Quick Settings with live modes](showcase/03_quick_settings_modes.png)](showcase/03_quick_settings_modes.png)

A side panel that reads the TV's real state: Picture mode (`Standard`), Audio mode (`DTS: Clarity + Virtualizer`), Network, and the connected Bluetooth audio device (`CMF Buds`).

### 04 · Recents

[![Recent apps menu](showcase/04_recent_apps_menu.png)](showcase/04_recent_apps_menu.png)

A tvOS-style Recent Apps menu, opened with a long press on Home. It lists running user apps (TizenTube, Cx File Explorer, CPU Info) with per-app RAM badges, a total memory pill (`Free: 541 MB / 1.7 GB`), and `Close App` / `Close All` actions.

### 05 · Animation settings

[![App launch animation settings](showcase/05_app_launch_settings.png)](showcase/05_app_launch_settings.png)

Tune the iOS-style launch animation: speed, easing curve, background depth, card artwork morph, and a live preview.

### 06 · Intro settings

[![Intro settings](showcase/06_intro_settings.png)](showcase/06_intro_settings.png)

Customize the boot and wake intro: wake gap threshold, hold duration (Auto or timed), animation style presets, and message modes.

### 07 · Phone Bridge

[![Phone Bridge pairing](showcase/07_phone_bridge_pairing.png)](showcase/07_phone_bridge_pairing.png)

Pair your phone with the TV. The screen shows the endpoint URL (`http://192.168.1.37:8765`), a 5-minute security PIN, and a live QR code to scan.

### 08 · Inputs

[![Translucent inputs menu](showcase/08_translucent_inputs_menu.png)](showcase/08_translucent_inputs_menu.png)

A frosted, translucent inputs popup with a morphing pill highlight over `Ashu's laptop · HDMI 1`, HDMI 2, HDMI 3, and AV.

---

## Good to know

- **Real hardware only.** All captures come from the live TV. Screenshots are pulled over ADB with `adb exec-out screencap -p`.
- **Built for one TV, tuned on it.** The Xiaomi-specific features (live picture and sound modes) depend on Xiaomi's TV middleware. Other Android TVs may work, but those parts will fall back quietly.
- **Showcase directory.** All raw 4K screenshots and 1080p MP4 recordings are located in the [showcase/](showcase/) folder.

---

<div align="center">

**CleanYourTV Reimagined** · a personal fork built on CleanYourTV by Gathin

<sub>Unofficial and not affiliated with the original project. Check the upstream license before reusing or redistributing anything.</sub>

</div>
