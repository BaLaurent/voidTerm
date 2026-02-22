package com.voidterm.voice;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects high-performance CPU cores for optimal whisper.cpp thread count.
 * Adapted from the official whisper.cpp Android example (CpuInfo + WhisperCpuConfig).
 *
 * On ARM big.LITTLE SoCs (like Quest's Snapdragon XR2), using only the high-perf
 * cores avoids scheduling inference on slow efficiency cores.
 */
public class CpuInfo {

    private static final String TAG = "CpuInfo";

    private final List<String> lines;

    CpuInfo(List<String> lines) {
        this.lines = lines;
    }

    /**
     * Returns the optimal thread count for whisper.cpp inference.
     * Uses high-perf core count, with a minimum of 2.
     */
    public static int getPreferredThreadCount() {
        try {
            int highPerf = readCpuInfo().getHighPerfCpuCount();
            int result = Math.max(highPerf, 2);
            Log.i(TAG, "Preferred thread count: " + result + " (high-perf cores: " + highPerf + ")");
            return result;
        } catch (Exception e) {
            int available = Runtime.getRuntime().availableProcessors();
            int fallback = Math.max((available + 1) / 2, 2);
            Log.i(TAG, "Couldn't read CPU info, falling back to " + fallback
                    + " (availableProcessors: " + available + ")", e);
            return fallback;
        }
    }

    int getHighPerfCpuCount() {
        try {
            int count = getHighPerfCpuCountByFrequencies();
            Log.i(TAG, "Frequency method: " + count + " high-perf cores");
            return count;
        } catch (Exception e) {
            Log.i(TAG, "Frequency method failed: " + e.getMessage());
            try {
                int count = getHighPerfCpuCountByVariant();
                Log.i(TAG, "Variant method: " + count + " high-perf cores");
                return count;
            } catch (Exception e2) {
                Log.i(TAG, "Variant method failed: " + e2.getMessage());
                throw e2;
            }
        }
    }

    private int getHighPerfCpuCountByFrequencies() {
        List<Integer> frequencies = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("processor")) {
                String value = line.substring(line.indexOf(':') + 1).trim();
                try {
                    int cpuIndex = Integer.parseInt(value);
                    frequencies.add(getMaxCpuFrequency(cpuIndex));
                } catch (IOException | NumberFormatException e) {
                    // skip this core (SELinux may block cpufreq access)
                }
            }
        }
        if (frequencies.isEmpty()) {
            throw new RuntimeException("No CPU frequencies found");
        }
        frequencies.sort(Integer::compareTo);
        Log.i(TAG, "CPU frequencies (freq, count): " + binValues(frequencies));
        int count = countDroppingMin(frequencies);
        if (count == 0) {
            // All readable cores have the same frequency — likely partial read (SELinux)
            throw new RuntimeException("All " + frequencies.size()
                    + " readable cores have same frequency, detection unreliable");
        }
        return count;
    }

    private int getHighPerfCpuCountByVariant() {
        List<Integer> variants = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("CPU variant")) {
                String value = line.substring(line.indexOf(':') + 1).trim();
                int hexStart = value.indexOf("0x");
                if (hexStart >= 0) {
                    variants.add(Integer.parseInt(value.substring(hexStart + 2), 16));
                }
            }
        }
        if (variants.isEmpty()) {
            throw new RuntimeException("No CPU variants found");
        }
        variants.sort(Integer::compareTo);
        Log.d(TAG, "CPU variants (variant, count): " + binValues(variants));
        return countKeepingMin(variants);
    }

    private static Map<Integer, Integer> binValues(List<Integer> values) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int v : values) {
            counts.put(v, counts.getOrDefault(v, 0) + 1);
        }
        return counts;
    }

    private static int countDroppingMin(List<Integer> values) {
        int min = Integer.MAX_VALUE;
        for (int v : values) {
            if (v < min) min = v;
        }
        int count = 0;
        for (int v : values) {
            if (v > min) count++;
        }
        return count;
    }

    private static int countKeepingMin(List<Integer> values) {
        int min = Integer.MAX_VALUE;
        for (int v : values) {
            if (v < min) min = v;
        }
        int count = 0;
        for (int v : values) {
            if (v == min) count++;
        }
        return count;
    }

    private static CpuInfo readCpuInfo() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return new CpuInfo(lines);
        }
    }

    private static int getMaxCpuFrequency(int cpuIndex) throws IOException {
        String path = "/sys/devices/system/cpu/cpu" + cpuIndex + "/cpufreq/cpuinfo_max_freq";
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return Integer.parseInt(reader.readLine().trim());
        }
    }
}
