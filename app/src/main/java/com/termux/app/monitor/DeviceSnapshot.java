package com.termux.app.monitor;

import android.app.ActivityManager;
import android.content.Context;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

/** An optional, best-effort device snapshot. Collect on a worker thread. */
public final class DeviceSnapshot {
    public final Long totalMemoryBytes, availableMemoryBytes;
    public final Boolean lowMemory;
    public final Integer batteryPercent, thermalStatus;

    private DeviceSnapshot(Long total, Long available, Boolean low, Integer battery, Integer thermal) {
        totalMemoryBytes = total; availableMemoryBytes = available; lowMemory = low;
        batteryPercent = battery; thermalStatus = thermal;
    }

    public static Long memoryUsedBytes(long total, long available) {
        return total <= 0 || available < 0 || available > total ? null : total - available;
    }

    public static DeviceSnapshot collect(Context context) {
        Long total = null, available = null;
        Boolean low = null;
        Integer battery = null, thermal = null;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(info);
                if (memoryUsedBytes(info.totalMem, info.availMem) != null) {
                    total = info.totalMem; available = info.availMem; low = info.lowMemory;
                }
            }
        } catch (RuntimeException ignored) { }
        try {
            BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (manager != null) {
                int value = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (value >= 0 && value <= 100) battery = value;
            }
        } catch (RuntimeException ignored) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (manager != null) {
                    int value = manager.getCurrentThermalStatus();
                    if (value >= PowerManager.THERMAL_STATUS_NONE && value <= PowerManager.THERMAL_STATUS_SHUTDOWN)
                        thermal = value;
                }
            } catch (RuntimeException ignored) { }
        }
        return new DeviceSnapshot(total, available, low, battery, thermal);
    }
}
