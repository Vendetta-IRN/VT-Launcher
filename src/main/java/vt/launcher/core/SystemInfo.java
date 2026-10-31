package vt.launcher.core;

import java.lang.management.ManagementFactory;

public final class SystemInfo {
    private SystemInfo() {}

    /** Physical RAM in MB (falls back to 8192 if it cannot be read). */
    public static long totalMb() {
        try {
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean m) {
                return m.getTotalMemorySize() / (1024 * 1024);
            }
        } catch (Throwable ignored) {
        }
        return 8192;
    }
}
