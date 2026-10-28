package com.termux.app.extrakeys;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExtraKeyPropertyStoreInstrumentedTest {
    private File directory;
    @Before public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = File.createTempFile("extra-keys-test-", "", context.getCacheDir());
        assertTrue(directory.delete()); assertTrue(directory.mkdir());
    }
    @After public void tearDown() {
        File[] entries = directory.listFiles();
        if (entries != null) for (File file : entries) file.delete();
        directory.delete();
    }
    @Test public void savesBackupAndPreservesExistingProperties() throws Exception {
        File file = new File(directory, "termux.properties");
        byte[] original = "# preserved\nfoo=bar\n".getBytes(StandardCharsets.UTF_8);
        write(file, original);
        ExtraKeyPropertyStore.Snapshot snapshot = ExtraKeyPropertyStore.read(file);
        ExtraKeyLayout layout = ExtraKeyLayout.parse("[[ESC,{key:'TAB',popup:'CTRL'}]]");
        ExtraKeyPropertyStore.SaveResult result = ExtraKeyPropertyStore.save(snapshot, layout);
        assertNotNull(result.backup); assertArrayEquals(original, read(result.backup));
        assertEquals(0600, Os.stat(result.backup.getAbsolutePath()).st_mode & 0777);
        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) { properties.load(reader); }
        assertEquals("bar", properties.getProperty("foo"));
        assertEquals(layout.toJson(), properties.getProperty("extra-keys"));
    }
    @Test public void detectsConcurrentChangeAndCreateRace() throws Exception {
        File file = new File(directory, "termux.properties"); write(file, "a=1\n".getBytes(StandardCharsets.UTF_8));
        ExtraKeyPropertyStore.Snapshot snapshot = ExtraKeyPropertyStore.read(file);
        write(file, "a=2\n".getBytes(StandardCharsets.UTF_8));
        try { ExtraKeyPropertyStore.save(snapshot, ExtraKeyLayout.parse("[[ESC]]")); fail(); }
        catch (ExtraKeyPropertyStore.ConflictException expected) { }
        assertArrayEquals("a=2\n".getBytes(StandardCharsets.UTF_8), read(file));
        File missing = new File(directory, "new.properties");
        ExtraKeyPropertyStore.Snapshot absent = ExtraKeyPropertyStore.read(missing);
        write(missing, "other=1\n".getBytes(StandardCharsets.UTF_8));
        try { ExtraKeyPropertyStore.save(absent, ExtraKeyLayout.parse("[[ESC]]")); fail(); }
        catch (ExtraKeyPropertyStore.ConflictException expected) { }
        assertArrayEquals("other=1\n".getBytes(StandardCharsets.UTF_8), read(missing));
    }
    @Test public void recoveryFingerprintDetectsContentAndFileIdentityChanges() throws Exception {
        File file = new File(directory, "termux.properties");
        byte[] contents = "a=1\n".getBytes(StandardCharsets.UTF_8);
        byte[] absent = ExtraKeyPropertyStore.read(file).fingerprint();
        write(file, contents);
        byte[] first = ExtraKeyPropertyStore.read(file).fingerprint();
        assertFalse(java.util.Arrays.equals(absent, first));
        assertArrayEquals(first, ExtraKeyPropertyStore.read(file).fingerprint());
        write(file, "a=2\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(java.util.Arrays.equals(first, ExtraKeyPropertyStore.read(file).fingerprint()));
        File replacement = new File(directory, "replacement");
        write(replacement, contents);
        assertTrue(replacement.renameTo(file));
        assertFalse(java.util.Arrays.equals(first, ExtraKeyPropertyStore.read(file).fingerprint()));
        assertFalse(java.util.Arrays.equals(ExtraKeyPropertyStore.read(file).fingerprint(),
            ExtraKeyPropertyStore.read(new File(directory, "other")).fingerprint()));
    }
    @Test public void createsNewFilePrivatelyAndRejectsLinks() throws Exception {
        File fresh = new File(directory, "new.properties");
        ExtraKeyPropertyStore.Snapshot absent = ExtraKeyPropertyStore.read(fresh);
        assertNull(ExtraKeyPropertyStore.save(absent, ExtraKeyLayout.parse("[[ESC]]")).backup);
        assertEquals(0600, Os.stat(fresh.getAbsolutePath()).st_mode & 0777);
        File symlink = new File(directory, "symlink"); Os.symlink(fresh.getAbsolutePath(), symlink.getAbsolutePath());
        try { ExtraKeyPropertyStore.read(symlink); fail(); } catch (IOException expected) { }
        File hardlink = new File(directory, "hardlink"); Os.link(fresh.getAbsolutePath(), hardlink.getAbsolutePath());
        assertEquals(2, Os.stat(fresh.getAbsolutePath()).st_nlink);
        try { ExtraKeyPropertyStore.read(fresh); fail(); } catch (IOException expected) { }
    }
    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
    }
    private static byte[] read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buf = new byte[256]; int n; while ((n = input.read(buf)) >= 0) output.write(buf, 0, n); return output.toByteArray();
        }
    }
}
