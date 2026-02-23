# Volume Keys Mapping Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add configurable actions for Volume Up and Volume Down buttons, independently, with 5 options each (Default, Escape, Toggle Keyboard, Macro, Voice Input).

**Architecture:** Replicate the back key behavior pattern. Constants in `SettingsDialog`, UI in `SettingsActivity` (new accordion section), key interception in `TermuxActivity.onKeyDown()`. No changes to `VoidTermTerminalViewClient` or `TerminalView`.

**Tech Stack:** Java, Android SharedPreferences, programmatic Android UI.

---

### Task 1: Add Volume Key Constants to SettingsDialog

**Files:**
- Modify: `app/src/main/java/com/voidterm/app/SettingsDialog.java:23-44`

**Step 1: Add constants after line 44 (after `BACK_VOICE`)**

Add these constants between `BACK_VOICE` (line 44) and `DEFAULT_MODEL` (line 45):

```java
    public static final String KEY_VOLUME_UP_BEHAVIOR = "volume_up_behavior";
    public static final String KEY_VOLUME_DOWN_BEHAVIOR = "volume_down_behavior";
    public static final String KEY_VOLUME_UP_MACRO = "volume_up_macro";
    public static final String KEY_VOLUME_DOWN_MACRO = "volume_down_macro";
    public static final String VOLUME_DEFAULT = "default";
```

**Step 2: Run tests to verify no compilation break**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.app.*" -q`
Expected: PASS (constants are additive, no breaking changes)

**Step 3: Commit**

```bash
git add app/src/main/java/com/voidterm/app/SettingsDialog.java
git commit -m "feat: add volume key mapping preference constants"
```

---

### Task 2: Add Volume Keys Section to SettingsActivity

**Files:**
- Modify: `app/src/main/java/com/voidterm/app/SettingsActivity.java`

**Step 1: Expand section count and add new section index**

At lines 44-48, change:

```java
    private static final int SECTION_BACK_KEY = 3;
    private static final int SECTION_COUNT = 4;
```

to:

```java
    private static final int SECTION_BACK_KEY = 3;
    private static final int SECTION_VOLUME_KEYS = 4;
    private static final int SECTION_COUNT = 5;
```

**Step 2: Add volume macro fields to class fields**

After `macroField` (line 68), add:

```java
    private EditText volumeUpMacroField;
    private EditText volumeDownMacroField;
```

**Step 3: Add section header + body in `onCreate` after the back key section**

After line 109 (`root.addView(sectionBodies[SECTION_BACK_KEY]);`), add:

```java
        buildSectionHeader(SECTION_VOLUME_KEYS, "Volume Keys", theme.modifier);
        sectionBodies[SECTION_VOLUME_KEYS] = buildVolumeKeysSection();
        root.addView(sectionBodies[SECTION_VOLUME_KEYS]);
```

**Step 4: Save volume macro fields in `onPause`**

After the `macroField` save block (line 141), add:

```java
        if (volumeUpMacroField != null) {
            prefs.edit().putString(SettingsDialog.KEY_VOLUME_UP_MACRO,
                    volumeUpMacroField.getText().toString()).apply();
        }
        if (volumeDownMacroField != null) {
            prefs.edit().putString(SettingsDialog.KEY_VOLUME_DOWN_MACRO,
                    volumeDownMacroField.getText().toString()).apply();
        }
```

**Step 5: Build the `buildVolumeKeysSection()` method**

Add after `buildBackKeySection()` (after line 662):

```java
    // --- Section: Volume Keys ---

    private LinearLayout buildVolumeKeysSection() {
        LinearLayout body = makeSectionBody();

        String[] volOptions = {"Default (system volume)", "Escape", "Toggle Keyboard", "Macro", "Voice Input"};
        String[] volValues = {SettingsDialog.VOLUME_DEFAULT, SettingsDialog.BACK_ESCAPE,
                SettingsDialog.BACK_TOGGLE_KEYBOARD, SettingsDialog.BACK_MACRO,
                SettingsDialog.BACK_VOICE};

        // --- Volume Up ---
        body.addView(makeSubheading("Volume Up"));

        String currentUp = prefs.getString(SettingsDialog.KEY_VOLUME_UP_BEHAVIOR,
                SettingsDialog.VOLUME_DEFAULT);
        Spinner upSpinner = makeSpinner(volOptions);
        upSpinner.setSelection(findIndex(volValues, currentUp));
        body.addView(upSpinner);

        volumeUpMacroField = new EditText(this);
        volumeUpMacroField.setHint("Macro command (e.g. {ctrl+c})");
        volumeUpMacroField.setTextColor(textColor);
        volumeUpMacroField.setHintTextColor(hintColor);
        volumeUpMacroField.setTextSize(14);
        volumeUpMacroField.setSingleLine(true);
        volumeUpMacroField.setText(prefs.getString(SettingsDialog.KEY_VOLUME_UP_MACRO, ""));
        volumeUpMacroField.setVisibility(
                SettingsDialog.BACK_MACRO.equals(currentUp) ? View.VISIBLE : View.GONE);
        body.addView(volumeUpMacroField);

        upSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString(SettingsDialog.KEY_VOLUME_UP_BEHAVIOR,
                        volValues[pos]).apply();
                volumeUpMacroField.setVisibility(pos == 3 ? View.VISIBLE : View.GONE);
            }
        });

        body.addView(makeDivider());

        // --- Volume Down ---
        body.addView(makeSubheading("Volume Down"));

        String currentDown = prefs.getString(SettingsDialog.KEY_VOLUME_DOWN_BEHAVIOR,
                SettingsDialog.VOLUME_DEFAULT);
        Spinner downSpinner = makeSpinner(volOptions);
        downSpinner.setSelection(findIndex(volValues, currentDown));
        body.addView(downSpinner);

        volumeDownMacroField = new EditText(this);
        volumeDownMacroField.setHint("Macro command (e.g. {ctrl+c})");
        volumeDownMacroField.setTextColor(textColor);
        volumeDownMacroField.setHintTextColor(hintColor);
        volumeDownMacroField.setTextSize(14);
        volumeDownMacroField.setSingleLine(true);
        volumeDownMacroField.setText(prefs.getString(SettingsDialog.KEY_VOLUME_DOWN_MACRO, ""));
        volumeDownMacroField.setVisibility(
                SettingsDialog.BACK_MACRO.equals(currentDown) ? View.VISIBLE : View.GONE);
        body.addView(volumeDownMacroField);

        downSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString(SettingsDialog.KEY_VOLUME_DOWN_BEHAVIOR,
                        volValues[pos]).apply();
                volumeDownMacroField.setVisibility(pos == 3 ? View.VISIBLE : View.GONE);
            }
        });

        return body;
    }
```

**Step 6: Verify build**

Run: `./gradlew assembleDebug -q 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add app/src/main/java/com/voidterm/app/SettingsActivity.java
git commit -m "feat: add Volume Keys settings section with independent Up/Down config"
```

---

### Task 3: Add Volume Key Handling to TermuxActivity

**Files:**
- Modify: `app/src/main/java/com/voidterm/app/TermuxActivity.java:532-541`

**Step 1: Add volume key interception in `onKeyDown()`**

At line 537 (after the back key check, before `return super.onKeyDown`), add:

```java
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && handleCustomVolumeKey(keyCode)) {
            return true;
        }
```

**Step 2: Add `handleCustomVolumeKey()` method**

Add after `handleCustomBackKey()` (after line 575):

```java
    private boolean handleCustomVolumeKey(int keyCode) {
        SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
        String key = (keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                ? SettingsDialog.KEY_VOLUME_UP_BEHAVIOR
                : SettingsDialog.KEY_VOLUME_DOWN_BEHAVIOR;
        String behavior = prefs.getString(key, SettingsDialog.VOLUME_DEFAULT);

        if (SettingsDialog.VOLUME_DEFAULT.equals(behavior)) {
            return false;
        }

        if (SettingsDialog.BACK_ESCAPE.equals(behavior)) {
            TerminalSession current = getCurrentSession();
            if (current != null) {
                current.write("\033");
            }
            return true;
        }

        if (SettingsDialog.BACK_TOGGLE_KEYBOARD.equals(behavior)) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
            }
            return true;
        }

        if (SettingsDialog.BACK_MACRO.equals(behavior)) {
            String macroKey = (keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                    ? SettingsDialog.KEY_VOLUME_UP_MACRO
                    : SettingsDialog.KEY_VOLUME_DOWN_MACRO;
            String macro = prefs.getString(macroKey, "");
            TerminalSession current = getCurrentSession();
            if (!macro.isEmpty() && current != null) {
                MacroExecutor.execute(macro, current::write,
                        terminalView != null ? terminalView.getHandler() : null);
            }
            return true;
        }

        if (SettingsDialog.BACK_VOICE.equals(behavior)) {
            onVoiceToggle();
            return true;
        }

        return false;
    }
```

**Step 3: Verify build**

Run: `./gradlew assembleDebug -q 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/java/com/voidterm/app/TermuxActivity.java
git commit -m "feat: handle configurable volume key actions in TermuxActivity"
```

---

### Task 4: Update CLAUDE.md Documentation

**Files:**
- Modify: `CLAUDE.md`

**Step 1: Add Volume Keys section after Back Key Behavior**

Find the "### Back Key Behavior" section and add after it:

```markdown
### Volume Key Behavior

Volume Up and Volume Down are independently configurable via `SettingsActivity` (Spinner). Five modes each, persisted in `SharedPreferences` ("voidterm_settings" / "volume_up_behavior" and "volume_down_behavior"):
- **Default** (default): system volume control, key not intercepted.
- **Escape**: sends `\033` to the terminal session.
- **Toggle Keyboard**: toggles soft input.
- **Macro**: executes a user-defined macro command (stored in "volume_up_macro" / "volume_down_macro") via `MacroExecutor`. Supports full `{tag}` syntax.
- **Voice Input**: triggers `onVoiceToggle()`.

Handled entirely in `TermuxActivity.handleCustomVolumeKey(int keyCode)` — no `VoidTermTerminalViewClient` or `TerminalView` layer needed (unlike back key).
```

**Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add volume key behavior documentation to CLAUDE.md"
```

---

### Task 5: Run Full Test Suite

**Step 1: Run all unit tests**

Run: `./gradlew testDebugUnitTest -q`
Expected: All tests PASS (no existing behavior modified)

**Step 2: Verify debug APK builds**

Run: `./gradlew assembleDebug -q 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL
