# Volume Keys Mapping Design

**Date:** 2026-02-24
**Status:** Approved

## Goal

Add configurable actions for Volume Up and Volume Down buttons, replicating the existing back key behavior pattern. Each button is independently configurable with 5 options: Default (system volume), Escape, Toggle Keyboard, Macro, Voice Input.

## Requirements

- Volume Up and Volume Down configured independently (separate Spinner + macro field each)
- Default behavior: system volume (no interception)
- Same action options as back key + "Default" option
- Same macro syntax support via `MacroExecutor`

## Design

### Constants (SettingsDialog)

```java
// Preference keys
public static final String KEY_VOLUME_UP_BEHAVIOR = "volume_up_behavior";
public static final String KEY_VOLUME_DOWN_BEHAVIOR = "volume_down_behavior";
public static final String KEY_VOLUME_UP_MACRO = "volume_up_macro";
public static final String KEY_VOLUME_DOWN_MACRO = "volume_down_macro";

// Shared behavior value (new)
public static final String VOLUME_DEFAULT = "default";
// Reuses existing: BACK_ESCAPE, BACK_TOGGLE_KEYBOARD, BACK_MACRO, BACK_VOICE
```

### SettingsActivity — UI

New "Volume Keys" section in the Interface accordion, containing:
- **Volume Up** Spinner: Default, Escape, Toggle Keyboard, Macro, Voice Input
- **Volume Up Macro** EditText: visible only when "Macro" selected
- **Volume Down** Spinner: same 5 options
- **Volume Down Macro** EditText: visible only when "Macro" selected

Same pattern as existing back key section (Spinner + conditional EditText).

### TermuxActivity — Key Handling

New method `handleCustomVolumeKey(int keyCode)` called from `onKeyDown()`:

```
Volume Up/Down pressed
  -> TermuxActivity.onKeyDown(KEYCODE_VOLUME_UP/DOWN)
    -> handleCustomVolumeKey(keyCode)
      +-- DEFAULT -> return false (system volume)
      +-- ESCAPE -> session.write("\033") -> return true
      +-- TOGGLE_KEYBOARD -> toggleSoftInput -> return true
      +-- MACRO -> MacroExecutor.execute() -> return true
      +-- VOICE -> onVoiceToggle() -> return true
```

### Not Modified

- **VoidTermTerminalViewClient**: No changes needed. Unlike back key (which has a TerminalView layer via `onKeyPreIme`), volume keys are handled entirely at the Activity level.
- **MacroExecutor**: Reused as-is.
- **ControlPanel / GameBoyControlPanel / CompactPanel / CompactToolbar**: No changes.

## Files to Modify

1. `SettingsDialog.java` — Add 4 new constants + 1 shared value constant
2. `SettingsActivity.java` — Add Volume Keys UI section in Interface accordion
3. `TermuxActivity.java` — Add `handleCustomVolumeKey()` + intercept in `onKeyDown()`
