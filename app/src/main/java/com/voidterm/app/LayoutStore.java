package com.voidterm.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Persistence for custom button positions in GameBoyControlPanel.
 * Stores positions as fractional coordinates (0.0-1.0) of panel dimensions.
 * Pattern follows MacroStore: static methods, SharedPreferences backend.
 */
public class LayoutStore {

    private static final String PREFS_NAME = "voidterm_layout";
    private static final String PREFS_KEY = "positions";

    public static Map<String, float[]> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(PREFS_KEY, null);
        if (json == null) return null;
        try {
            JSONObject root = new JSONObject(json);
            Map<String, float[]> result = new HashMap<>();
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject pos = root.getJSONObject(key);
                result.put(key, new float[]{
                        (float) pos.getDouble("x"),
                        (float) pos.getDouble("y")
                });
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    public static void save(Context context, Map<String, float[]> positions) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, float[]> entry : positions.entrySet()) {
                JSONObject pos = new JSONObject();
                pos.put("x", entry.getValue()[0]);
                pos.put("y", entry.getValue()[1]);
                root.put(entry.getKey(), pos);
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREFS_KEY, root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(PREFS_KEY).apply();
    }

    public static boolean hasCustomLayout(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .contains(PREFS_KEY);
    }
}
