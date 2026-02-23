package com.voidterm.testutil;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HashMap-backed SharedPreferences for JVM unit tests (no Robolectric needed).
 */
public class FakeSharedPreferences implements SharedPreferences {

    private final Map<String, Object> data = new HashMap<>();
    private final List<OnSharedPreferenceChangeListener> listeners = new ArrayList<>();

    @Override public String getString(String key, String defValue) {
        return data.containsKey(key) ? (String) data.get(key) : defValue;
    }
    @Override public int getInt(String key, int defValue) {
        return data.containsKey(key) ? (int) data.get(key) : defValue;
    }
    @Override public long getLong(String key, long defValue) {
        return data.containsKey(key) ? (long) data.get(key) : defValue;
    }
    @Override public float getFloat(String key, float defValue) {
        return data.containsKey(key) ? (float) data.get(key) : defValue;
    }
    @Override public boolean getBoolean(String key, boolean defValue) {
        return data.containsKey(key) ? (boolean) data.get(key) : defValue;
    }
    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String key, Set<String> defValues) {
        if (!data.containsKey(key)) return defValues;
        return new HashSet<>((Set<String>) data.get(key));
    }
    @Override public boolean contains(String key) { return data.containsKey(key); }
    @Override public Map<String, ?> getAll() { return new HashMap<>(data); }
    @Override public Editor edit() { return new FakeEditor(); }

    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        listeners.add(l);
    }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        listeners.remove(l);
    }

    private class FakeEditor implements Editor {
        @Override public Editor putString(String key, String value) { data.put(key, value); return this; }
        @Override public Editor putInt(String key, int value) { data.put(key, value); return this; }
        @Override public Editor putLong(String key, long value) { data.put(key, value); return this; }
        @Override public Editor putFloat(String key, float value) { data.put(key, value); return this; }
        @Override public Editor putBoolean(String key, boolean value) { data.put(key, value); return this; }
        @Override public Editor putStringSet(String key, Set<String> values) {
            data.put(key, values != null ? new HashSet<>(values) : null); return this;
        }
        @Override public Editor remove(String key) { data.remove(key); return this; }
        @Override public Editor clear() { data.clear(); return this; }
        @Override public boolean commit() { return true; }
        @Override public void apply() {}
    }
}
