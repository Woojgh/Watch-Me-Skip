# Project Explanation
## What this project is
This project is focused on one core behavior: **automatic skip-button detection with remote human-initiated skipping**.

The Android app detects likely skip actions on-screen, then waits for a remote human confirmation (watch tap or earbud gesture) before executing the skip.

## Core flow: detect → prompt → human confirm → execute
1. The app detects a likely skip target from the live screen (for example, text containing “skip”).
2. It opens a short remote confirmation window (about 20 seconds).
3. It sends a watch-style prompt (`SKIP`) and also listens for supported earbud media gestures.
4. A human confirms remotely (watch action or earbud next/fast-forward gesture).
5. The app re-validates the current screen and safety checks, then performs the skip.
6. If confirmation never comes, the app changes, or timeout occurs, it cancels safely.

## Why this matters
- keeps a human in the loop for skip actions
- removes repeated manual tapping on skip prompts
- supports hands-busy contexts through remote confirmation
- keeps the behavior local/on-device with no cloud dependency

## Main components
- `app/`: Android implementation, including remote-skip detection and confirmation pipeline
- `app/src/main/java/com/example/aiassistant/RemoteSkipController.kt`: watch/earbud prompt and confirmation orchestration
- `app/src/main/java/com/example/aiassistant/AutoAccessibilityService.kt`: live detection and remote-confirmed execution path
- `README.md`: broader project overview and setup details

## Safety and control model
- remote skip acts only inside a short confirmation window
- action is re-validated against current live UI before execution
- safety checks and cooldowns still apply before any click
- pending requests are cancelled on timeout, dismissal, or screen/app change

## How to run
### Android
1. Open the repo in Android Studio.
2. Sync Gradle and run on API 26+.
3. Enable the accessibility service from the app.
4. Use **REMOTE_SKIP** mode to test auto-detect + remote human confirmation.
5. Optionally use the watch prompt test button to verify watch action flow.

### iOS
iOS does not support Android-style cross-app accessibility control, so remote skip automation is Android-first in this branch.

## Standalone branch snapshot note
This repository is a standalone snapshot of `feature/remote-skip-watch-earbuds`, intended for independent development and sharing of the remote human-initiated skip workflow.
