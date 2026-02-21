# Quest Setup Guide

This guide covers installing and configuring VoidTerm on Meta Quest.

## Prerequisites

- Meta Quest 2, Quest 3, or Quest Pro
- USB-C cable (for initial setup)
- PC with ADB installed
- VoidTerm APK file

## 1. Enable Developer Mode

1. Open the **Meta Quest mobile app** on your phone
2. Go to **Settings > System > Developer**
3. Toggle **Developer Mode** on
4. Restart your Quest headset

Alternatively, on the headset: **Settings > System > Developer** (requires a registered developer account at [developer.oculus.com](https://developer.oculus.com)).

## 2. Install VoidTerm

Connect your Quest to a PC via USB-C and install:

```bash
adb install voidterm.apk
```

To update an existing installation:

```bash
adb install -r voidterm.apk
```

VoidTerm appears in the Quest app library under **Unknown Sources**.

## 3. Disable Phantom Process Killing (CRITICAL)

Android 12+ enforces a 32-process limit across all apps. Claude Code with Node.js and bash creates 5-10 child processes. Without this fix, Android will randomly kill Claude Code processes during use.

**Run these commands once via ADB:**

```bash
adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
adb shell settings put global settings_enable_monitor_phantom_procs false
```

These settings persist across reboots. You only need to run them once.

**Verification:**

```bash
adb shell "/system/bin/device_config get activity_manager max_phantom_processes"
# Expected output: 2147483647
```

> **Warning:** Without this fix, Claude Code sessions will crash unpredictably. This is the single most important step in the setup process.

## 4. Bluetooth Keyboard

A Bluetooth keyboard is strongly recommended for terminal use.

**Pairing:**

1. On Quest: **Settings > Devices > Bluetooth**
2. Put your keyboard in pairing mode
3. Select it from the list of available devices

**Verified key support:**

- All letters, numbers, symbols
- Enter, Tab, Escape, arrow keys
- Ctrl+C, Ctrl+D, Ctrl+Z
- All standard terminal shortcuts

Voice input and keyboard input coexist without conflict.

## 5. Controller Mapping

Quest controllers provide the following mappings in VoidTerm:

| Button | Action |
|--------|--------|
| **Trigger** (index) | Pointer / Click (native Android) |
| **A or X** | Push-to-Talk (hold to record, release to transcribe) |
| **B or Y** | Back |
| **Thumbstick up/down** | Scroll terminal |

The Push-to-Talk button is configurable in VoidTerm settings.

## 6. Recommendations

- **Disable hand tracking** during terminal use to avoid accidental input. Go to **Settings > Movement Tracking > Hand Tracking** and toggle it off.
- **Font size:** VoidTerm defaults to 20sp for Quest readability. Adjust in VoidTerm settings if needed.
- **Extra keys:** The on-screen extra keys row is sized at 150% for Quest raycast precision. Use them for special characters (Tab, Ctrl, Esc, etc.).
- **Session length:** VoidTerm has been tested for 30+ minute sessions. Monitor Quest thermal comfort during extended use.

## 7. Launching

1. Put on your Quest headset
2. Open the app library
3. Filter by **Unknown Sources**
4. Launch **VoidTerm**
5. On first launch, the onboarding wizard guides you through package installation and Claude Code setup

## Troubleshooting

### "App not installed" error

Ensure Developer Mode is enabled and the APK is signed. Try uninstalling any previous version first:

```bash
adb uninstall com.voidterm
adb install voidterm.apk
```

### Claude Code processes getting killed

Re-run the phantom process killing fix (Section 3). Verify the setting is applied with the verification command.

### No audio for voice input

Grant microphone permission when prompted on first use. If denied, go to Quest **Settings > Apps > VoidTerm > Permissions** and enable Microphone.

### Controller buttons not responding

Ensure controllers are connected and charged. Restart VoidTerm if button mapping stops working after a Quest system update.
