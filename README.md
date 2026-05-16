# Watch Me Skip

An Android app that detects skip buttons on your screen and lets you confirm the skip from your smartwatch or earbuds — no phone tap required.

No cloud. No LLM. Fully on-device. Human stays in the loop.

---

## Why This Exists

You're cooking, driving, working out, or carrying groceries. An ad starts playing with a tiny "Skip" button. You'd tap it instantly if your hands were free — but they're not.

This app watches for skip-like targets on screen, then sends a prompt to your watch or listens for an earbud gesture. One tap on your wrist or a double-press of your earbuds, and the skip fires. If you don't confirm, nothing happens.

---

## How It Works

The app runs as an Android Accessibility Service with four modes:

---

### Off
Disables all observation and automation.

---

## Remote Confirmation Methods

**Smartwatch (Wear OS)**
The app posts a high-priority notification with an inline "SKIP" action that mirrors to any paired Wear OS watch. Tap the action on your wrist to confirm.

**Earbuds (play next or next song gesture)**
The app registers a media session that captures next-track and fast-forward hardware key events. A double-press or next-track gesture on most Bluetooth earbuds triggers confirmation.

**Voice (say "skip")**
When enabled, the app starts a speech recognizer during the confirmation window that listens through your earbud mic (or phone mic if no earbuds are connected). Say "skip" to confirm. Uses on-device recognition when available (Android 12+), falls back to standard speech services on older devices. Requires microphone permission.

All methods play a short chime when the confirmation window opens so you know a skip is available.

---

## Safety

The app will not:
- Click anything containing "purchase", "buy", "pay", "delete", "uninstall", "reset", "subscribe", "billing", or similar dangerous keywords
- Act on apps you've excluded in Safety Settings
- Take any action it hasn't observed at least 3 times (in Assist mode)
- Fire actions faster than every 800ms
- Execute a remote skip without live re-validation of the screen
- Act at all in Observe or Off mode

Every action — taken, blocked, or expired — is logged with timestamps for review.

---

## Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                    AutoAccessibilityService                        │
│                                                                    │
│  ┌──────────────┐  ┌───────────────┐  ┌────────────────────────┐  │
│  │  Observer     │  │ AssistEngine  │  │  RemoteSkipController  │  │
│  │  (OBSERVE +   │  │ (ASSIST only) │  │  (ASSIST + REMOTE_SKIP)│  │
│  │   ASSIST)     │  │               │  │                        │  │
│  │              │  │ PatternMatcher│  │  Watch notification     │  │
│  │  Records     │  │ SafetyChecker │  │  MediaSession earbuds   │  │
│  │  user taps,  │  │ ActionExecutor│  │  Timeout / cancellation │  │
│  │  scrolls,    │  │               │  │  Live re-validation     │  │
│  │  text input  │  │               │  │                        │  │
│  └──────┬───────┘  └──────┬────────┘  └───────────┬────────────┘  │
│         │                 │                        │               │
│  ┌──────┴─────────────────┴────────────────────────┴───────────┐  │
│  │  ScreenReactor + SystemStateMonitor                         │  │
│  │  Correlates system setting changes with screen context       │  │
│  │  Auto-applies learned volume/brightness/ringer patterns     │  │
│  └─────────────────────────────┬───────────────────────────────┘  │
│                                │                                  │
│  ┌─────────────────────────────┴───────────────────────────────┐  │
│  │                      Room Database                          │  │
│  │  UserPatternEntity  SystemPatternEntity  LogEntity          │  │
│  └─────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

---

## Setup

### Requirements
- Android Studio (Arctic Fox or later)
- Android SDK 26+ (Android 8.0 Oreo)
- JDK 17
- Optional: Wear OS watch or Bluetooth earbuds for remote skip confirmation

### Build & Install
1. Clone the repository
2. Open in Android Studio
3. Sync Gradle
4. Run on a device or emulator (API 26+)

### First Launch
1. Open the app
2. Tap **Open Accessibility Settings** and enable "AI Assistant"
3. Grant notification permission (required for watch prompts)
4. Grant overlay permission for the floating status bubble
5. Use **Test Watch Prompt** to verify your watch receives the SKIP notification
6. Switch to **Remote Skip Mode** for skip-only automation, or **Observe Mode** to start learning broader patterns first

---

## Permissions

- `BIND_ACCESSIBILITY_SERVICE` — observes and interacts with UI across apps
- `SYSTEM_ALERT_WINDOW` — floating status overlay
- `FOREGROUND_SERVICE` — keeps the overlay service running
- `POST_NOTIFICATIONS` — sends skip prompts to your watch
- `RECORD_AUDIO` — voice skip via earbud/phone microphone (only when voice skip is enabled)

Optional: `WRITE_SETTINGS` (for brightness/rotation control) — the app prompts for this and works without it.

---

## Privacy

- All data stored locally in a Room (SQLite) database on-device
- No network requests, no analytics, no telemetry
- No data leaves your phone
- All learned patterns are viewable and deletable from within the app

---

## Tech Stack

- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Database**: Room 2.6.1 with KSP
- **Async**: Kotlin Coroutines 1.7.3
- **UI**: Programmatic Android views (no XML layouts)
- **Build**: Gradle 8.13 with Kotlin DSL

---

## Branch Note

This repository is a standalone snapshot of the `feature/remote-skip-watch-earbuds` branch, focused on the remote human-confirmed skip workflow.

---

## License

GPL-3.0 — see [LICENSE](LICENSE) for details.
