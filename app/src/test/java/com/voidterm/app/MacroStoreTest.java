package com.voidterm.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for {@link MacroStore} load/save persistence and migration logic.
 * Uses Robolectric for real SharedPreferences and org.json support.
 */
@RunWith(RobolectricTestRunner.class)
public class MacroStoreTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // Clear macro prefs before each test
        context.getSharedPreferences("voidterm_macros", Context.MODE_PRIVATE)
                .edit().clear().apply();
    }

    // ---------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------

    @Test
    public void constants_haveExpectedValues() {
        assertEquals(12, MacroStore.MACRO_COUNT);
        assertEquals(4, MacroStore.PAGE_SIZE);
        assertEquals(3, MacroStore.PAGE_COUNT);
    }

    // ---------------------------------------------------------------
    // load() — first launch (no prefs)
    // ---------------------------------------------------------------

    @Test
    public void load_noPrefs_returnsDefaults() {
        String[][] macros = MacroStore.load(context);
        assertEquals(12, macros.length);
        assertEquals("/clear", macros[0][0]);
        assertEquals("clear", macros[0][1]);
        assertEquals("/compact", macros[1][0]);
        assertEquals("export TERM_COMPACT=1", macros[1][1]);
    }

    // ---------------------------------------------------------------
    // Round-trip save/load
    // ---------------------------------------------------------------

    @Test
    public void saveAndLoad_roundTrip_preservesAllMacros() {
        String[][] custom = new String[12][2];
        for (int i = 0; i < 12; i++) {
            custom[i][0] = "label" + i;
            custom[i][1] = "cmd" + i;
        }
        MacroStore.save(context, custom);

        String[][] loaded = MacroStore.load(context);
        assertEquals(12, loaded.length);
        for (int i = 0; i < 12; i++) {
            assertEquals("label" + i, loaded[i][0]);
            assertEquals("cmd" + i, loaded[i][1]);
        }
    }

    @Test
    public void saveAndLoad_preservesAllLabelsAndCommandsExactly() {
        String[][] custom = new String[12][2];
        custom[0] = new String[]{"My Clear", "clear && ls"};
        custom[1] = new String[]{"Git Status", "git status"};
        custom[2] = new String[]{"Special Chars", "echo \"hello world\" | grep -o 'h'"};
        for (int i = 3; i < 12; i++) {
            custom[i] = new String[]{"m" + i, "echo " + i};
        }
        MacroStore.save(context, custom);

        String[][] loaded = MacroStore.load(context);
        for (int i = 0; i < 12; i++) {
            assertEquals(custom[i][0], loaded[i][0]);
            assertEquals(custom[i][1], loaded[i][1]);
        }
    }

    // ---------------------------------------------------------------
    // Migration from old 4-macro format
    // ---------------------------------------------------------------

    @Test
    public void load_migrationFrom4Macros_preservesFourFillsDefaults() throws Exception {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < 4; i++) {
            JSONObject obj = new JSONObject();
            obj.put("label", "old" + i);
            obj.put("cmd", "oldcmd" + i);
            arr.put(obj);
        }
        putRawJson(arr.toString());

        String[][] loaded = MacroStore.load(context);
        assertEquals(12, loaded.length);
        // First 4 preserved from old data
        for (int i = 0; i < 4; i++) {
            assertEquals("old" + i, loaded[i][0]);
            assertEquals("oldcmd" + i, loaded[i][1]);
        }
        // Remaining 8 filled with defaults (indices 4-11)
        assertEquals("macro5", loaded[4][0]);
        assertEquals("echo 5", loaded[4][1]);
        assertEquals("macro12", loaded[11][0]);
        assertEquals("echo 12", loaded[11][1]);
    }

    // ---------------------------------------------------------------
    // Migration from unexpected length (e.g., 7)
    // ---------------------------------------------------------------

    @Test
    public void load_migrationFrom7Macros_preservesSevenFillsDefaults() throws Exception {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < 7; i++) {
            JSONObject obj = new JSONObject();
            obj.put("label", "custom" + i);
            obj.put("cmd", "customcmd" + i);
            arr.put(obj);
        }
        putRawJson(arr.toString());

        String[][] loaded = MacroStore.load(context);
        assertEquals(12, loaded.length);
        // First 7 preserved
        for (int i = 0; i < 7; i++) {
            assertEquals("custom" + i, loaded[i][0]);
            assertEquals("customcmd" + i, loaded[i][1]);
        }
        // Remaining 5 filled with defaults (indices 7-11)
        assertEquals("macro8", loaded[7][0]);
        assertEquals("echo 8", loaded[7][1]);
        assertEquals("macro12", loaded[11][0]);
        assertEquals("echo 12", loaded[11][1]);
    }

    // ---------------------------------------------------------------
    // Corrupted / invalid JSON
    // ---------------------------------------------------------------

    @Test
    public void load_corruptedJson_returnsDefaults() {
        putRawJson("this is not valid json{{{");

        String[][] loaded = MacroStore.load(context);
        assertEquals(12, loaded.length);
        assertEquals("/clear", loaded[0][0]);
        assertEquals("clear", loaded[0][1]);
    }

    // ---------------------------------------------------------------
    // Empty JSON array
    // ---------------------------------------------------------------

    @Test
    public void load_emptyJsonArray_returnsDefaults() {
        putRawJson("[]");

        String[][] loaded = MacroStore.load(context);
        assertEquals(12, loaded.length);
        assertEquals("/clear", loaded[0][0]);
        assertEquals("clear", loaded[0][1]);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private void putRawJson(String json) {
        context.getSharedPreferences("voidterm_macros", Context.MODE_PRIVATE)
                .edit().putString("macros", json).apply();
    }
}
