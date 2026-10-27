package com.termux.app.terminal.clipboard;

import junit.framework.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

public class ImageStreamImporterTest extends TestCase {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 1, 4};
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P'};

    public void testCopiesAllowedSignaturesAtLimit() throws Exception {
        checkCopy(PNG, "image/png");
        checkCopy(JPEG, "image/jpeg");
        checkCopy(WEBP, "image/webp");
    }

    private void checkCopy(byte[] input, String mime) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(input.length, ImageStreamImporter.copy(new ByteArrayInputStream(input), output, mime, input.length));
        assertTrue(Arrays.equals(input, output.toByteArray()));
    }

    public void testCacheBudgetCountsFilesWithoutRemovingThem() throws Exception {
        File directory = File.createTempFile("termux-image-cache-test-", "");
        assertTrue(directory.delete());
        assertTrue(directory.mkdir());
        File first = new File(directory, "image-a.png");
        File second = new File(directory, "import-b.partial");
        try {
            try (FileOutputStream out = new FileOutputStream(first)) { out.write(new byte[8]); }
            try (FileOutputStream out = new FileOutputStream(second)) { out.write(new byte[3]); }
            assertEquals(9, ClipboardImageImporter.cacheRemaining(directory, 20));
            assertEquals(0, ClipboardImageImporter.cacheRemaining(directory, 11));
            assertTrue(first.isFile());
            assertTrue(second.isFile());
        } finally {
            first.delete();
            second.delete();
            directory.delete();
        }
    }

    public void testCleanupRemovesOnlyStalePartialFiles() throws Exception {
        File directory = File.createTempFile("termux-cache-cleanup-", "");
        assertTrue(directory.delete());
        assertTrue(directory.mkdir());
        File oldPartial = new File(directory, "import-old.partial");
        File freshPartial = new File(directory, "import-fresh.partial");
        File completed = new File(directory, "image-old.png");
        long now = System.currentTimeMillis();
        try {
            assertTrue(oldPartial.createNewFile());
            assertTrue(freshPartial.createNewFile());
            assertTrue(completed.createNewFile());
            assertTrue(oldPartial.setLastModified(now - 2L * 24 * 60 * 60 * 1000));
            assertTrue(completed.setLastModified(now - 2L * 24 * 60 * 60 * 1000));
            ClipboardImageImporter.removeStalePartialFiles(directory, now);
            assertFalse(oldPartial.exists());
            assertTrue(freshPartial.exists());
            assertTrue(completed.exists());
        } finally {
            oldPartial.delete();
            freshPartial.delete();
            completed.delete();
            directory.delete();
        }
    }

    public void testSpecificFailureReasons() throws Exception {
        assertReason(PNG, "image/jpeg", 100, ImageImportException.Reason.UNSUPPORTED);
        assertReason(new byte[5], "image/png", 100, ImageImportException.Reason.UNSUPPORTED);
        assertReason(PNG, "image/png", 12, ImageImportException.Reason.SIZE);
    }

    private void assertReason(byte[] input, String mime, long max, ImageImportException.Reason expected)
        throws Exception {
        try {
            ImageStreamImporter.copy(new ByteArrayInputStream(input), new ByteArrayOutputStream(), mime, max);
            fail("Expected input rejection");
        } catch (ImageImportException e) {
            assertEquals(expected, e.getReason());
        }
    }

    public void testPathQuoting() {
        assertEquals("'/private/path/image.png'", ClipboardImageImporter.quotePath("/private/path/image.png"));
        assertEquals("'/a'\\''b'", ClipboardImageImporter.quotePath("/a'b"));
        assertEquals("'/first image.png' '/second.jpg'", ClipboardImageImporter.quotePaths(
            Arrays.asList(new File("/first image.png"), new File("/second.jpg"))));
    }

    public void testRejectsMismatchedShortAndOversizeInput() throws Exception {
        assertRejected(PNG, "image/jpeg", 100);
        assertRejected(new byte[5], "image/png", 100);
        assertRejected(PNG, "image/png", PNG.length - 1);
        assertRejected(PNG, "image/gif", 100);
    }

    private void assertRejected(byte[] input, String mime, int max) throws Exception {
        try {
            ImageStreamImporter.copy(new ByteArrayInputStream(input), new ByteArrayOutputStream(), mime, max);
            fail("Expected input rejection");
        } catch (IOException expected) {
            // Expected.
        }
    }
}
