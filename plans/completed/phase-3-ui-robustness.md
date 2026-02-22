# Phase 3: UI robustness - Completion Report

**Completed**: 2026-02-22
**Plan file**: plans/CODE_REVIEW.md

## Tasks Executed

### Task 1: Backport cancelRepeat/onDetachedFromWindow from CompactToolbar to GameBoyControlPanel
**Status**: ✅ Success
**File**: `GameBoyControlPanel.java`
**Actions taken**:
- Added `activeRepeatRunnable` and `activeRepeatView` fields
- Refactored `setupArrowRepeat()` to call `cancelRepeat()` before starting new repeat, track active repeat in fields, and cancel on `ACTION_UP`/`ACTION_CANCEL`
- Added `cancelRepeat()` method (matches CompactToolbar pattern)
- Added `onDetachedFromWindow()` override that calls `cancelRepeat()` and unregisters haptic listener
- **Pattern backported**: CompactToolbar's safe repeat cleanup, preventing NPE when view detached during held arrow press

### Task 2: Add streaming mode warning in SettingsDialog
**Status**: ✅ Success
**File**: `SettingsDialog.java`
**Actions taken**:
- Added orange warning TextView below the "Streaming transcription" checkbox
- Warning text: "⚠ Streaming sends text directly to the terminal without review. You cannot edit or cancel before submission."
- Warning visibility toggles with the streaming checkbox (shown only when enabled)
- Replaced single `OnCheckedChangeListener` to also control warning visibility
- **Decision**: Option (b) from code review — warning instead of full overlay review implementation

### Task 3: Cache isHapticEnabled in GameBoyControlPanel and CompactToolbar
**Status**: ✅ Success
**Files**: `GameBoyControlPanel.java`, `CompactToolbar.java`
**Actions taken**:
- Added `volatile boolean hapticEnabled` field to both panels
- Added `SharedPreferences.OnSharedPreferenceChangeListener hapticListener` to both panels
- Constructor reads initial value and registers listener
- `onDetachedFromWindow()` unregisters listener (added to GBCP, extended in CT)
- Replaced all `SettingsDialog.isHapticEnabled(getContext())` calls with direct field read:
  - GameBoyControlPanel: 13 sites replaced
  - CompactToolbar: 7 sites replaced
- **Total SharedPreferences reads eliminated**: 20 per-press reads → 0 (now volatile field read)

### Task 4: Call viewClient.release() in TermuxActivity.onDestroy()
**Status**: ✅ Success
**File**: `TermuxActivity.java`
**Actions taken**:
- Added `if (viewClient != null) viewClient.release();` in `onDestroy()`, before voiceInputManager cleanup
- This unregisters the `OnSharedPreferenceChangeListener` that was previously leaked

### Task 5: Add isDestroyed() guard in BootstrapCallback
**Status**: ✅ Success
**File**: `TermuxActivity.java`
**Actions taken**:
- Added `if (isDestroyed()) return;` guard to all 3 BootstrapCallback implementations:
  1. Initial `install()` callback (3 methods: onProgressUpdate, onInstallComplete, onInstallFailed)
  2. `repatch()` callback in `onBootstrapReady()` (3 methods)
  3. Retry `install()` callback in `showInstallError()` (3 methods)
- **Total guards added**: 9 (3 callbacks × 3 methods each)

### Task 6: Fix MacroStore graceful handling for unexpected array lengths
**Status**: ✅ Success
**File**: `MacroStore.java`
**Actions taken**:
- Unified the `len == 4` migration path and arbitrary-length handling into a single branch
- For `len != 4` (and `len != 12`): logs a warning with the unexpected length
- For any `len > 0 && len != 12`: preserves `min(len, MACRO_COUNT)` entries, fills remaining with defaults
- Saves the normalized 12-entry array back (migration happens once)
- Silent discard no longer occurs — data is preserved whenever possible

## Summary
- Tasks completed: 6/6
- Tasks fixed: 0 (all succeeded first try)
- Tasks failed: 0
- Files modified: `GameBoyControlPanel.java`, `CompactToolbar.java`, `SettingsDialog.java`, `TermuxActivity.java`, `MacroStore.java`
- Code review findings addressed: H6, H8, H9, H11, H12, M18
- SharedPreferences reads eliminated: 20+ per interaction (haptic) + 1 per destroy (viewClient listener leak)
- isDestroyed guards: 9 added across 3 bootstrap callback sites
- Build: ✅ assembleDebug passed
