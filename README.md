# 📱 Dynamic Island for Android (Native APK)

A native Android camera punch-hole overlay (`dynamicSpot` style) bringing the Apple Dynamic Island experience with real-time media player controls, animated **Squiggly Wavy Scrubber Bar**, charging animations, and notch calibration to any Android device.

---

## ✨ Features

- **Floating Punch-Hole Overlay**: Uses Android's `WindowManager` overlay to place a fluid capsule around your phone's front camera cutout (supports center, left, or right hole punches).
- **Interactive Squiggly Wavy Scrubber**: Real-time sinusoidal wavy progress bar that animates when music is playing, stays visibly squiggly when paused, and allows instant touch-seeking.
- **Universal Music Integration**: Built with Android's `MediaSessionManager` to read metadata (Title, Artist, Album Art, Duration, Progress) from **Spotify, YouTube Music, Apple Music, SoundCloud, Deezer, Chrome**, and any active player.
- **Transport Controls**: Hero Previous, Play/Pause, and Next buttons directly in the expanded card.
- **Live Battery Card**: Shows battery level, charging power when the device reports it, estimated time to full when available, and a low-battery alert.
- **Quick Countdown Timer**: Start 5, 15, or 30 minute timers from the island, pause or resume them, and get an alert when one finishes. Timer state survives service restarts.
- **Live Maps Directions**: Shows the next maneuver, ETA, and distance in meters, converting kilometer, feet, and mile distances when Maps publishes them.
- **Call Actions**: Offers green answer and red decline controls when Phone or WhatsApp exposes those actions in its incoming-call notification.
- **Notification Actions**: Adds a Mark as read button when the source app provides that notification action.
- **Expanded Now Playing**: Larger album art, current player label, live playback progress, seeking, volume, and transport controls.
- **Charging Indicator**: Automatically triggers a battery charging toast and green glow when connected to power.
- **Custom Notch Calibration**: Real-time sliders in the app to calibrate horizontal X offset, top margin Y, and compact pill width/height to perfectly match your specific phone model.

---

## 🚀 How to Download the APK

### Method 1: Push to GitHub & Download via GitHub Actions (Automated)

1. Create a new repository on your GitHub account (e.g. `DynamicIslandAndroid`).
2. Run these commands inside the `DynamicIslandAndroid` folder:
   ```bash
   git init
   git add .
   git commit -m "Initial Dynamic Island Android release"
   git branch -M main
   git remote add origin https://github.com/<your-username>/DynamicIslandAndroid.git
   git push -u origin main
   ```
3. Open your repository on GitHub and click the **Actions** tab.
4. The **Build Dynamic Island Android APK** workflow will automatically build your APK.
5. Once completed (typically ~2 minutes), click on the completed run:
   - Under **Artifacts**, download `DynamicIsland-APK` (or download from the generated **GitHub Release**).
   - Transfer or download directly onto your Android phone!

---

## 📲 How to Install & Set Up on Your Phone

1. **Install the APK:**
   - Tap the downloaded `DynamicIsland-v1.0.0.apk`.
   - If prompted by Android, tap **Settings** and allow **Install unknown apps**.
   - Tap **Install** and open the app.

2. **Grant Permissions:**
   - **Overlay Permission**: Tap **Grant** on the Overlay card and toggle **Allow display over other apps**.
   - **Notification & Media Access**: Tap **Grant** on the Notification card, find **Dynamic Island**, and toggle it **ON**.

3. **Calibrate Notch Position:**
   - Use the **Horizontal Offset (X)** slider if your front camera is on the left (e.g., Galaxy S10) or right.
   - Adjust the **Top Margin (Y)** slider so the pill sits around your camera cutout.
   - Adjust the **Compact Width** to your desired pill size.

4. **Turn On:**
   - Toggle the **Enable Dynamic Island** switch at the top.
   - Start playing any song on Spotify or YouTube Music, and tap the island at the top of your screen to expand it!

---

## 🛠️ Project Structure

```
DynamicIslandAndroid/
├── .github/workflows/
│   └── build-apk.yml          # GitHub Actions cloud compiler for APK
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/dynamicisland/app/
│   │   │   ├── MainActivity.kt               # Settings & Notch Calibration
│   │   │   ├── IslandOverlayService.kt       # Floating WindowManager overlay
│   │   │   ├── MediaNotificationListener.kt  # MediaSession & music controller
│   │   │   ├── BatteryReceiver.kt            # Charging broadcast listener
│   │   │   ├── PreferencesManager.kt         # Setting storage
│   │   │   └── view/
│   │   │       └── WavyProgressBar.kt        # Custom squiggly wavy scrubber
│   │   └── res/
│   │       ├── layout/
│   │       │   ├── activity_main.xml
│   │       │   ├── layout_island_compact.xml
│   │       │   └── layout_island_expanded.xml
│   │       └── values/
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```
