# CleanYourTV Reimagined — Project Guide & Directory Map

Welcome to **CleanYourTV Reimagined**! This repository contains the complete Android TV launcher source code, customized for fluid iOS-style motion, Xiaomi TV hardware controls, and an Apple TV-inspired aesthetic.

---

## ⚡ Quick Actions (1-Click Desktop Scripts)

Double-click any of these scripts directly in this folder:
- **`Build Release APK.bat`**: Runs all 389+ unit tests, builds the optimized release APK using Android Studio's bundled JDK, and outputs the final `.apk`.
- **`Install to TV.bat`**: Connects to the Xiaomi TV via Wi-Fi ADB (`192.168.1.37:5555`) and installs the latest built release APK.
- **`Sync TV Wallpapers.bat`**: Syncs curated 4K wallpapers from your PC to the TV internal storage with deduplication and format checks.

---

## 📁 Clean Directory Map

```
clean your tv - enhanced/
│
├── 🚀 1-Click Scripts & Config
│   ├── Build Release APK.bat    # 1-click test & release build
│   ├── Install to TV.bat        # 1-click install to TV over Wi-Fi
│   ├── Sync TV Wallpapers.bat   # 1-click 4K wallpaper sync
│   ├── sync_wallpapers.py       # Python script powering wallpaper sync
│   └── config.json              # Launcher configuration (dock apps, hidden apps, etc.)
│
├── 📱 Application Source
│   └── launcher/                # Main Android Studio Project
│       ├── app/src/main/java/   # Core Java classes (MainActivity, LauncherMotion, etc.)
│       ├── app/src/main/res/    # Layouts, animations, and drawables
│       ├── app/src/main/assets/ # Greetings database (greetings.json) & Phone Bridge web app
│       ├── app/src/test/        # Unit test suites (51 suites, 389+ unit tests)
│       └── gradlew.bat          # Gradle build tool
│
├── 🎨 Assets & Customization
│   ├── wallpapers/              # 4K bundled wallpapers
│   ├── themes/                  # Community & pre-built launcher themes
│   └── addons/                  # Optional launcher extensions
│
├── 📖 Documentation & Guides
│   ├── PROJECT_GUIDE.md         # This visual directory map and guide
│   ├── README.md                # Official project README (English)
│   ├── SHOWCASE.md              # Feature showcase & visual preview notes
│   └── docs/                    # Screenshots, feature specifications, and guides
│       └── translations/        # Translated READMEs (German, Japanese, Italian, etc.)
│
├── 🔬 Reverse-Engineering & Research
│   └── research/                # Decompiled Xiaomi TV middleware research archive
│       ├── scripts/             # 220+ Python bytecode exploration & DEX search scripts
│       ├── dex_and_classes/     # Extracted classes and test harnesses
│       ├── tv_oem_apks/         # OEM dumps (TvQS, MiTVSceneMode, TvService, etc.)
│       └── benchmarks/          # Performance benchmarks and traces
│
└── 🛠️ Developer Tools & Tests
    ├── tools/                   # Developer tools, bench utilities, and helpers
    ├── tests/                   # Shell and automated integration test scripts
    └── examples/                # Reference profiles for 400+ TV models
```

---

## 🛠️ How to Open in Android Studio

1. Open **Android Studio**.
2. Select **File > Open...**
3. Navigate to and select the **`launcher`** folder inside this directory:
   `C:\Users\Ashirwad\Desktop\projects\clean your tv - enhanced\launcher`
4. Let Gradle sync. You can run unit tests or launch directly to the TV from the Studio toolbar.

---

## 📺 Connected TV Hardware Specs

- **Model:** Xiaomi Smart TV 4K (`MiTV_MZTU0`, code name `river`)
- **OS:** Android 14 (TV Edition)
- **Wi-Fi ADB Endpoint:** `192.168.1.37:5555`
- **RAM / Storage:** ~1.7 GB total RAM, ~18.4 GB free storage
- **Phone Bridge Web App:** `http://192.168.1.37:8765` (secured via 5-minute PIN & QR pairing)

