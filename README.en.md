# PortalDroid — a Windows-to-Android bridge

<div align="center">

[Español](README.md) · **English**

</div>

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Android-blue)
![Status](https://img.shields.io/badge/status-stable-brightgreen)

## What is PortalDroid?

PortalDroid is an **open source** app that turns your Android into a second device you control from Windows—no screen mirroring, no virtual second monitor.

**How it works:**
Move your mouse to the edge of your Windows screen, a glowing halo appears, and from that point your cursor drives the phone's physical screen directly, over your local WiFi.

**Features:**
- 🖱️ **Full mouse control** — Movement, clicks and drags on the Android
- 🎡 **Working mouse wheel** — Scroll in apps like TikTok, Instagram, YouTube, Facebook, browsers, etc.
- ⌨️ **PC keyboard** — Type straight into text fields on the phone
- 🔊 **Android audio on Windows** — Hear any app's audio without changing Windows' own audio output
- 📱 **Keep screen on** (optional) — Set the phone to never sleep while you're using PortalDroid
- 🎨 **Fully customizable** — Change the halo's color, position and size, adjust sensitivity
- 🔒 **Local privacy** — Runs only on your WiFi, no cloud, no data collection

**Good for:**
Content creators or office workers who need to control their Android from the comfort of their PC's keyboard and mouse without stopping their work on Windows.

---

## ⬇️ Download

| | File | How to install |
|---|---|---|
| **Windows** | [**PortalDroid.exe**](https://github.com/alanepazs/PortalDroid/raw/main/PortalDroid.exe) | Double-click. Portable, installs nothing. |
| **Android** | [**PortalDroid.apk**](https://github.com/alanepazs/PortalDroid/raw/main/PortalDroid.apk) | Copy it to the phone and open it. |

> Both devices must be on the same WiFi network.
> Windows may show an "unknown publisher" warning: **More info → Run anyway**.
> Android will ask permission to **install from unknown sources**: allow it.

---

## Get going in 3 minutes

### 1. Install on Android

Download **PortalDroid.apk** (above) and open it on the phone.

**First time:** Follow step 1 → **"Allow it to control the screen"** → Settings → Accessibility → find **PortalDroid** and turn it on.

### 2. Run on Windows

Double-click **PortalDroid.exe** (portable, single file, installs nothing). It lives in the **hidden icons** of the system tray (the little ▲ arrow).

The first run opens a window with a **QR code**. Windows will ask about the firewall—**allow it on private networks** so the phone can reach the PC.

### 3. Pair the two

On the phone, step 2 → **"Scan the PC's code"** → point the camera at the QR.

Done. There's no address to type. You scan once, and from then on the phone reconnects on its own whenever both devices are on the same WiFi.

The code carries your PC's address and a secret key. That key stops anyone else on your WiFi from connecting to your phone—without having seen your screen, they can't get in.

### 4. Audio (optional)

Step 3 on the phone → **"Send audio to the PC"** → Android asks for capture permission (you have to grant it every time; it can't be remembered). The audio stops on its own when you close the app.

---

## How to use it

**Move the mouse to the halo.** It lights up and you're now driving the phone. A blue circle appears on the phone's screen showing where you're about to touch.

| Action on the PC | What it does on the phone |
|---|---|
| Move the mouse | Moves the pointer |
| Left click | Taps (hold it down to drag) |
| Mouse wheel | Scrolls—flick through TikTok videos |
| Type text | Types, if there's an open text field |
| `Esc` | Back button |
| `F1` / `F2` | Home / recent apps |

**To get back to Windows,** move the pointer to the opposite edge of the phone's screen. If it gets stuck, press `Ctrl+Alt+W`.

### Shortcuts

| Shortcut | What it does |
|---|---|
| `Ctrl+Alt+W` | Back to Windows (emergency exit) |
| `Ctrl+Alt+↑` / `↓` | Phone audio volume |
| `Ctrl+Alt+PgUp` / `PgDn` | Move the halo along the edge |
| `Ctrl+Alt+H` | Toggle the halo, to test it |
| `Ctrl+Alt+Shift+Q` | Quit the app |

If one of them doesn't respond, check the log: the app tells you when another application has already taken a shortcut.

---

## Settings

**Double-click the tray icon** to open the settings window. You'll see your monitors drawn to scale, in their real positions. Click the edge where you want the halo and it moves there instantly.

Edges shown **hatched** can't be used: those are the ones that touch another monitor, where the mouse crosses from one screen to the other. Putting the halo there would send you to the phone every time you switch screens. With two monitors you have **3 usable edges per screen**.

In the same window you adjust:
- **Position** along the edge (left/center/right or custom)
- **Length** of the halo (as % of the edge)
- **Color** (6 presets + free picker)
- **Always visible**: keep the halo faint but visible even when you're not crossing (easier to find)

### Advanced options

In `%APPDATA%\portaldroid-windows\config.json`:

| Option | What it does |
|---|---|
| `sensitivity` | Mouse distance per phone pixel (higher = faster) |
| `dwellMs` | Delay before crossing (0 = instant; if you cross by accident, move the halo instead of raising this) |
| `haloLengthFraction` | Halo length as a fraction of the edge (works at any resolution) |
| `scrollDistance` | How far one wheel click scrolls |
| `invertScroll` | Whether the wheel scrolls the other way |
| `audioBufferMs` | Audio buffer (higher if it cuts out; lower if it lags) |
| `triggerOnHaloOnly` | Cross only over the halo (true) or anywhere on the edge (false) |

---

## Requirements

### Windows
- Windows 10 or later (tested on Windows 11 Pro)
- TCP ports 7099 (touch) and 7100 (audio) free
- (See the note below if you use other apps as administrator)

### Android
- Android 12 (API level 31+) or later (tested on Android 16)
- Same WiFi network as the PC
- Accessibility Service permission

---

## Privacy

PortalDroid runs entirely on your local network and does not collect, store or send personal data to any remote server.

- ✓ **No analytics** — No usage tracking
- ✓ **No Internet connections** — Everything works offline on local WiFi
- ✓ **No data collection** — No locations, contacts, photos or history are stored
- ✓ **Local communication** — Only your PC and your phone talk to each other
- ✓ **No third-party services** — No Firebase, Google Cloud, Sentry or similar
- ✓ **Open source** — You can audit the code on GitHub

**Data it processes (all local):**
- Mouse coordinates (sent to the phone, not to the Internet)
- Keyboard input (processed locally)
- Phone audio (captured and sent to the PC, both on your network)

---

## Things worth knowing

### Audio

- **You'll hear the audio twice**—from the phone and from the PC speakers (Android won't let the phone mute itself while capturing). Note: **turning the phone's own volume down does NOT lower** what you hear on the PC, because the capture takes the audio before the system's volume control.
- **There's a separate control for that.** In the phone app, the "Volume sent to the PC" card raises or lowers the audio that gets sent, independent of the phone's volume and Windows'. It applies live, no need to restart the stream.
- **Some apps don't allow their audio to be captured** (Spotify and Netflix refuse). TikTok does allow it.

### Keyboard

- Works with text fields that are already focused
- Doesn't work in password fields or apps that draw their own keyboard
- For those you'd need a custom Android IME

### Monitors with different scaling

- If you have one monitor at 100% and another at 150%, the halo may be offset
- The app detects it and warns in the log, but it isn't fully solved
- Keep all monitors at the same scaling for now

### Programs running as administrator

If you use games with anti-cheat (which run as admin), PortalDroid won't see mouse events while they have focus. **Fix:** run PortalDroid as administrator too. The Desktop shortcut already does this; if you run `PortalDroid.exe` by hand, right-click → "Run as administrator".

**Why:** Windows doesn't deliver mouse events to normal apps while an elevated program has focus—it's a system protection.

### Single instance

If you open PortalDroid twice, the second copy closes itself. That's intentional—two instances fight over the mouse and nothing works.

---

## Build from source

### Android

```bash
cd android-app

# If Java isn't on your PATH, point at Android Studio's:
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"

# Build
./gradlew assembleDebug

# Install on the connected phone
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Watch the logs
adb logcat -s PortalDroid:*
```

### Windows

```bash
cd windows-app

# Development mode
npm install
npm start

# Packaged executable (~70 MB, portable, no install)
npm run build
# Output: dist/PortalDroid.exe
```

**Note:** When packaged, the config lives in `%APPDATA%\portaldroid-windows\config.json`—the one inside the .exe is read-only. The first run copies the bundled one there.

---

## Troubleshooting

### "The app won't start"
- Check your antivirus—it may be blocking the .exe
- Make sure ports 7099 and 7100 are free
- Try running as administrator

### "I can't find the halo"
- If "Always visible" is on, it's the faint blue bar on your chosen edge
- If not, move the mouse **exactly** to the edge—it only appears when you touch it
- Use `Ctrl+Alt+H` to toggle it, for testing

### "The phone won't connect"
- Both on the same WiFi?
- Windows firewall blocking it? You need to allow it on *private* networks
- Check the log from the tray: right-click → "Open the log (for diagnostics)"

### "The audio cuts out"
- Raise `audioBufferMs` in config.json (start with 300)
- Some apps (Spotify, Netflix) don't allow capture—try another
- Phone near a microwave? WiFi interference causes gaps

### "The pointer feels laggy"
- Adjust `sensitivity` in config.json (default 0.0025—higher numbers = faster)
- With two monitors: make sure both are at the same scaling

### "It disconnects all the time"
- Move the phone closer to the router
- Check whether another app is saturating the WiFi
- Restart the phone app and try again

---

## How it works

**Architecture:**

- **Windows app** (`windows-app/`): an Electron app in the system tray. Hooks the global mouse with `uiohook-napi`, detects the halo, opens a TCP server and sends touch/scroll/keyboard commands to the Android over WiFi.

- **Android service** (`android-app/`): an Accessibility Service that turns the TCP commands into real gestures with `dispatchGesture`. It also streams other apps' audio over TCP in real time.

**Why WiFi instead of USB?** USB (scrcpy) requires the phone's screen to be mirrored on the PC. PortalDroid works with the phone showing its *own* screen on a stand next to you, which is what actually makes it useful one-handed.

**Why an Accessibility Service?** No root needed. It's the only clean way to produce real touches without running as system.

**The mouse trick:** an "infinite mouse" trick warps the cursor back to a center point after every move, so the mouse never runs off the screen. Only the *delta* is sent to the phone and accumulated there.

Full TCP protocol details in [PROTOCOL.md](PROTOCOL.md).

---

## Known limitations

- **Click-and-hold:** Works. Right-click isn't mapped yet.
- **Multi-touch:** Single-finger input only for now.
- **Battery:** Some Android devices optimize the background process away. You may need to disable battery optimization for the app.

---

## Contributing

Bugs or ideas? Open an issue. Code? Fork, new branch, send a PR.

Areas that need help:
- Right-click mapping (long-press?)
- A custom Android IME for password fields
- Graceful handling of monitors with different scaling
- Better error messages for network problems

---

## License

**MIT** license. You're free to use, modify and distribute it, commercially or not. See [LICENSE](LICENSE) for the full text.

**Dependencies:** See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for third-party library licenses.

---

## Acknowledgements

Built over a week of intense debugging and measurement. Every feature (audio especially) went through several rounds of edge-case hunting to actually work. If something looks over-engineered, it's probably because it broke three times first.

---

**Tested on:** Windows 11 Pro, Android 16, 2560×1440 + 1920×1080 displays at 100% scaling.
