package com.termux.terminal;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/** Host smoke test for the JNI PTY, independent of the Android UI and Handler. */
public final class HeadlessPtySmoke {
    public static void main(String[] args) throws Exception {
        String shell = System.getenv("TERMUX_PTY_SHELL");
        if (shell == null || shell.isEmpty()) throw new IllegalArgumentException("Set TERMUX_PTY_SHELL");
        String failure = System.getenv("TERMUX_PTY_FAIL_TEST");
        if (failure != null && !failure.isEmpty()) {
            verifyStartupFailure(shell, failure);
            return;
        }
        int beforeInvalid = countDescriptors();
        try {
            JNI.createSubprocess(shell, "/", new String[]{"sh", "-c", "exit 0"},
                new String[]{"PATH=/system/bin:/usr/bin:/bin"}, new int[0], 24, 80, 8, 16);
            throw new AssertionError("Expected invalid PID array failure");
        } catch (RuntimeException expected) {
            if (countDescriptors() != beforeInvalid) throw new AssertionError("Invalid PID array leaked a descriptor");
        }
        int[] pid = new int[1];
        int raw = JNI.createSubprocess(shell, "/", new String[]{"sh", "-c", "printf 'PTY_OK\\n'; exit 7"},
            new String[]{"PATH=/system/bin:/system/xbin:/usr/bin:/bin"}, pid, 24, 80, 8, 16);
        if (raw < 0 || pid[0] <= 0) throw new AssertionError("PTY did not start");
        FileDescriptor descriptor = new FileDescriptor();
        Field field;
        try { field = FileDescriptor.class.getDeclaredField("descriptor"); }
        catch (NoSuchFieldException e) { field = FileDescriptor.class.getDeclaredField("fd"); }
        field.setAccessible(true);
        field.setInt(descriptor, raw);
        int status = JNI.waitFor(pid[0]);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(descriptor)) {
            byte[] buffer = new byte[256];
            for (;;) {
                try {
                    int count = input.read(buffer);
                    if (count < 0) break;
                    output.write(buffer, 0, count);
                } catch (IOException e) {
                    // Linux PTY masters commonly return EIO once the slave closes.
                    break;
                }
            }
        }
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        if (status != 7 || !text.contains("PTY_OK"))
            throw new AssertionError("Unexpected PTY result: status=" + status + ", output=" + text);
        if (JNI.waitFor(Integer.MAX_VALUE) != Integer.MIN_VALUE)
            throw new AssertionError("waitpid failure was not reported");
        System.out.println("PTY spawn, output, exit status and wait failure passed");
    }

    private static void verifyStartupFailure(String shell, String failure) throws Exception {
        // Load the library before counting descriptors so loader activity does not affect the result.
        Class.forName("com.termux.terminal.JNI");
        int before = countDescriptors();
        try {
            JNI.createSubprocess(shell, "/", new String[]{"sh", "-c", "exit 0"},
                new String[]{"PATH=/system/bin:/usr/bin:/bin"}, new int[1], 24, 80, 8, 16);
            throw new AssertionError("Expected injected " + failure + " failure");
        } catch (RuntimeException expected) {
            int after = countDescriptors();
            if (after != before) throw new AssertionError("Descriptor leak: " + before + " -> " + after);
            System.out.println("PTY " + failure + " failure released its descriptor");
        }
    }

    private static int countDescriptors() {
        String[] entries = new java.io.File("/proc/self/fd").list();
        if (entries == null) throw new AssertionError("Cannot inspect descriptors");
        return entries.length;
    }
}
