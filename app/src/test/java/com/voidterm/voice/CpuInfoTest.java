package com.voidterm.voice;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link CpuInfo} CPU core detection logic.
 *
 * In JVM tests, /sys/devices/system/cpu/ is unreadable, so the frequency
 * method always fails and falls back to the variant method. Tests exercise
 * the variant-based path via the package-private constructor and methods.
 */
public class CpuInfoTest {

    // ---------------------------------------------------------------
    // getHighPerfCpuCount() — variant method (frequency always fails in JVM)
    // ---------------------------------------------------------------

    @Test
    public void twoVariants_fourEach_returnsCountOfMinVariant() {
        // 4 cores variant 0x1 (min), 4 cores variant 0x2
        List<String> lines = Arrays.asList(
                "processor\t: 0",
                "CPU variant\t: 0x1",
                "processor\t: 1",
                "CPU variant\t: 0x1",
                "processor\t: 2",
                "CPU variant\t: 0x1",
                "processor\t: 3",
                "CPU variant\t: 0x1",
                "processor\t: 4",
                "CPU variant\t: 0x2",
                "processor\t: 5",
                "CPU variant\t: 0x2",
                "processor\t: 6",
                "CPU variant\t: 0x2",
                "processor\t: 7",
                "CPU variant\t: 0x2"
        );
        CpuInfo cpuInfo = new CpuInfo(lines);
        assertEquals(4, cpuInfo.getHighPerfCpuCount());
    }

    @Test
    public void threeVariants_returnsCountOfMinVariant() {
        // 2 cores variant 0x0 (min), 3 cores variant 0x1, 3 cores variant 0x2
        List<String> lines = Arrays.asList(
                "CPU variant\t: 0x0",
                "CPU variant\t: 0x0",
                "CPU variant\t: 0x1",
                "CPU variant\t: 0x1",
                "CPU variant\t: 0x1",
                "CPU variant\t: 0x2",
                "CPU variant\t: 0x2",
                "CPU variant\t: 0x2"
        );
        CpuInfo cpuInfo = new CpuInfo(lines);
        assertEquals(2, cpuInfo.getHighPerfCpuCount());
    }

    @Test
    public void questXr2Layout_returnsCountOfMinVariant() {
        // Quest XR2: 4 processor entries, 4 variant 0x1 (min), 4 variant 0x2
        List<String> lines = Arrays.asList(
                "processor\t: 0",
                "model name\t: ARMv8 Processor rev 14 (v8l)",
                "CPU variant\t: 0x1",
                "processor\t: 1",
                "model name\t: ARMv8 Processor rev 14 (v8l)",
                "CPU variant\t: 0x1",
                "processor\t: 2",
                "model name\t: ARMv8 Processor rev 14 (v8l)",
                "CPU variant\t: 0x1",
                "processor\t: 3",
                "model name\t: ARMv8 Processor rev 14 (v8l)",
                "CPU variant\t: 0x1",
                "processor\t: 4",
                "model name\t: ARMv8 Processor rev 14 (v8l)",
                "CPU variant\t: 0x2",
                "processor\t: 5",
                "model name\t: ARMv8 Processor rev 14 (v8l)",
                "CPU variant\t: 0x2",
                "processor\t: 6",
                "model name\t: ARMv8 Processor rev 14 (v8l)",
                "CPU variant\t: 0x2",
                "processor\t: 7",
                "model name\t: ARMv8 Processor rev 14 (v8l)",
                "CPU variant\t: 0x2"
        );
        CpuInfo cpuInfo = new CpuInfo(lines);
        assertEquals(4, cpuInfo.getHighPerfCpuCount());
    }

    @Test
    public void allSameVariant_returnsAll() {
        // 8 cores all variant 0x1 — all are min, so returns 8
        List<String> lines = Arrays.asList(
                "CPU variant\t: 0x1",
                "CPU variant\t: 0x1",
                "CPU variant\t: 0x1",
                "CPU variant\t: 0x1",
                "CPU variant\t: 0x1",
                "CPU variant\t: 0x1",
                "CPU variant\t: 0x1",
                "CPU variant\t: 0x1"
        );
        CpuInfo cpuInfo = new CpuInfo(lines);
        assertEquals(8, cpuInfo.getHighPerfCpuCount());
    }

    @Test
    public void singleCore_returnsOne() {
        List<String> lines = Arrays.asList(
                "processor\t: 0",
                "CPU variant\t: 0x3"
        );
        CpuInfo cpuInfo = new CpuInfo(lines);
        assertEquals(1, cpuInfo.getHighPerfCpuCount());
    }

    @Test
    public void emptyLinesMixedIn_skipsNonMatchingLines() {
        List<String> lines = Arrays.asList(
                "",
                "CPU variant\t: 0x1",
                "",
                "some other info line",
                "CPU variant\t: 0x1",
                "",
                "CPU variant\t: 0x2",
                ""
        );
        CpuInfo cpuInfo = new CpuInfo(lines);
        // min is 0x1, count of 0x1 = 2
        assertEquals(2, cpuInfo.getHighPerfCpuCount());
    }

    @Test(expected = RuntimeException.class)
    public void malformedVariantLines_noHexPrefix_throwsRuntimeException() {
        // Variant lines without "0x" are skipped, resulting in empty variants list
        List<String> lines = Arrays.asList(
                "CPU variant\t: 1",
                "CPU variant\t: 2"
        );
        CpuInfo cpuInfo = new CpuInfo(lines);
        cpuInfo.getHighPerfCpuCount();
    }

    @Test(expected = RuntimeException.class)
    public void emptyList_throwsRuntimeException() {
        CpuInfo cpuInfo = new CpuInfo(Collections.emptyList());
        cpuInfo.getHighPerfCpuCount();
    }

    @Test
    public void getPreferredThreadCount_returnsAtLeastTwo() {
        // Static method reads /proc/cpuinfo, which fails in JVM.
        // Falls back to (availableProcessors + 1) / 2, minimum 2.
        int result = CpuInfo.getPreferredThreadCount();
        assertTrue("Preferred thread count should be >= 2, was " + result,
                result >= 2);
    }

    @Test(expected = RuntimeException.class)
    public void processorLinesButNoVariantLines_throwsRuntimeException() {
        // Processor lines present but no variant lines — frequency method fails
        // (no sysfs in JVM), variant method throws (no variants found)
        List<String> lines = Arrays.asList(
                "processor\t: 0",
                "processor\t: 1",
                "processor\t: 2",
                "processor\t: 3"
        );
        CpuInfo cpuInfo = new CpuInfo(lines);
        cpuInfo.getHighPerfCpuCount();
    }
}
