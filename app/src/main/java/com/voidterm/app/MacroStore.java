package com.voidterm.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Shared macro persistence for GameBoyControlPanel and CompactToolbar.
 * Stores 12 macros (3 pages of 4) in SharedPreferences as a JSON array.
 * Handles migration from the old 4-macro format automatically.
 */
public class MacroStore {

    private static final String TAG = "MacroStore";
    public static final int MACRO_COUNT = 12;
    public static final int PAGE_SIZE = 4;
    public static final int PAGE_COUNT = 3;

    private static final String PREFS_NAME = "voidterm_macros";
    private static final String PREFS_KEY = "macros";

    private static final String[][] DEFAULT_MACROS = {
            {"/clear", "clear"},
            {"/compact", "export TERM_COMPACT=1"},
            {"macro3", "echo 3"},
            {"macro4", "echo 4"},
            {"macro5", "echo 5"},
            {"macro6", "echo 6"},
            {"macro7", "echo 7"},
            {"macro8", "echo 8"},
            {"macro9", "echo 9"},
            {"macro10", "echo 10"},
            {"macro11", "echo 11"},
            {"macro12", "echo 12"},
    };

    public static String[][] load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(PREFS_KEY, null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                int len = arr.length();
                if (len == MACRO_COUNT) {
                    String[][] result = new String[MACRO_COUNT][2];
                    for (int i = 0; i < MACRO_COUNT; i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        result[i][0] = obj.getString("label");
                        result[i][1] = obj.getString("cmd");
                    }
                    return result;
                }
                if (len == 4) {
                    // Migration from old 4-macro format
                    String[][] result = new String[MACRO_COUNT][2];
                    for (int i = 0; i < 4; i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        result[i][0] = obj.getString("label");
                        result[i][1] = obj.getString("cmd");
                    }
                    for (int i = 4; i < MACRO_COUNT; i++) {
                        result[i][0] = DEFAULT_MACROS[i][0];
                        result[i][1] = DEFAULT_MACROS[i][1];
                    }
                    save(context, result);
                    return result;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load macros", e);
            }
        }
        // Return defaults
        String[][] result = new String[MACRO_COUNT][2];
        for (int i = 0; i < MACRO_COUNT; i++) {
            result[i][0] = DEFAULT_MACROS[i][0];
            result[i][1] = DEFAULT_MACROS[i][1];
        }
        return result;
    }

    public static void save(Context context, String[][] macros) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < MACRO_COUNT; i++) {
                JSONObject obj = new JSONObject();
                obj.put("label", macros[i][0]);
                obj.put("cmd", macros[i][1]);
                arr.put(obj);
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREFS_KEY, arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to save macros", e);
        }
    }
}
