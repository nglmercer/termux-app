package com.termux.app.terminal.clipboard;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

/** Imports one content URI into Termux's private cache. Call only on an I/O thread. */
public final class ClipboardImageImporter {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final long MAX_BATCH_BYTES = 40L * 1024 * 1024;
    private static final long MAX_CACHE_BYTES = 100L * 1024 * 1024;
    private static final long PARTIAL_MAX_AGE_MS = 24L * 60 * 60 * 1000;
    public static final int MAX_BATCH_ITEMS = 8;
    private ClipboardImageImporter() {}

    /** Returns every file in clipboard order; deletes the entire batch on failure. */
    public static synchronized List<File> importImages(Context context, List<Uri> uris) throws IOException {
        if (uris.isEmpty() || uris.size() > MAX_BATCH_ITEMS) throw new IOException("Invalid image count");
        File cacheRoot = context.getCacheDir();
        if (cacheRoot == null) throw new IOException("Image cache unavailable");
        File directory = new File(cacheRoot, "clipboard-images");
        if ((!directory.isDirectory() && !directory.mkdir()) || !directory.isDirectory())
            throw new IOException("Cannot create image cache");
        if (!directory.getCanonicalFile().equals(new File(cacheRoot.getCanonicalFile(), "clipboard-images")))
            throw new IOException("Image cache is redirected");
        removeStalePartialFiles(directory, System.currentTimeMillis());
        List<File> files = new ArrayList<>();
        long available = cacheRemaining(directory, MAX_CACHE_BYTES);
        if (available < 12) throw new ImageImportException(ImageImportException.Reason.CACHE);
        long remaining = Math.min(MAX_BATCH_BYTES, available);
        boolean completed = false;
        try {
            for (Uri uri : uris) {
                if (remaining < 12) throw new ImageImportException(available < MAX_BATCH_BYTES
                    ? ImageImportException.Reason.CACHE : ImageImportException.Reason.SIZE);
                File file;
                try {
                    file = importImage(context, directory, uri, Math.min(MAX_BYTES, remaining));
                } catch (ImageImportException e) {
                    if (e.getReason() == ImageImportException.Reason.SIZE
                        && available < MAX_BATCH_BYTES && remaining < MAX_BYTES)
                        throw new ImageImportException(ImageImportException.Reason.CACHE);
                    throw e;
                }
                files.add(file);
                remaining -= file.length();
            }
            completed = true;
            return files;
        } finally {
            if (!completed) for (File file : files) file.delete();
        }
    }

    /** Remove only old temporary files; completed images have a different name and are retained. */
    static void removeStalePartialFiles(File directory, long now) throws IOException {
        File[] entries = directory.listFiles();
        if (entries == null) throw new IOException("Cannot inspect image cache");
        File canonicalDirectory = directory.getCanonicalFile();
        for (File entry : entries) {
            String name = entry.getName();
            long modified = entry.lastModified();
            if (!name.startsWith("import-") || !name.endsWith(".partial") || !entry.isFile()
                || modified <= 0 || modified > now || now - modified < PARTIAL_MAX_AGE_MS) continue;
            // Do not follow or remove links placed in the cache directory.
            if (!entry.getCanonicalFile().equals(new File(canonicalDirectory, name))) continue;
            entry.delete();
        }
    }

    /** Count existing regular files without deleting anything a shell may still be using. */
    static long cacheRemaining(File directory, long limit) throws IOException {
        File[] entries = directory.listFiles();
        if (entries == null) throw new IOException("Cannot inspect image cache");
        long remaining = limit;
        for (File entry : entries) {
            if (!entry.isFile()) throw new IOException("Unexpected image cache entry");
            long size = entry.length();
            if (size >= remaining) return 0;
            remaining -= size;
        }
        return remaining;
    }

    private static File importImage(Context context, File directory, Uri uri, long maxBytes) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme())) throw new ImageImportException(ImageImportException.Reason.UNSUPPORTED);
        ContentResolver resolver = context.getContentResolver();
        String mime = resolver.getType(uri);
        String extension;
        if ("image/png".equals(mime)) extension = ".png";
        else if ("image/jpeg".equals(mime)) extension = ".jpg";
        else if ("image/webp".equals(mime)) extension = ".webp";
        else throw new ImageImportException(ImageImportException.Reason.UNSUPPORTED);

        File temporary = File.createTempFile("import-", ".partial", directory);
        boolean moved = false;
        try {
            try {
                Os.chmod(temporary.getAbsolutePath(), 0600);
            } catch (ErrnoException e) {
                throw new IOException("Cannot protect image file", e);
            }
            InputStream source;
            try {
                source = resolver.openInputStream(uri);
            } catch (FileNotFoundException e) {
                throw new ImageImportException(ImageImportException.Reason.ACCESS);
            }
            if (source == null) throw new ImageImportException(ImageImportException.Reason.ACCESS);
            try (InputStream input = source; FileOutputStream output = new FileOutputStream(temporary)) {
                ImageStreamImporter.copy(input, output, mime, maxBytes);
            }
            File destination = new File(directory, "image-" + UUID.randomUUID() + extension);
            if (!temporary.renameTo(destination)) throw new IOException("Cannot save image");
            moved = true;
            return destination;
        } finally {
            if (!moved) temporary.delete();
        }
    }

    /** Quote each path separately, preserving the order of the clipboard items. */
    public static String quotePaths(List<File> files) {
        StringBuilder result = new StringBuilder();
        for (File file : files) {
            if (result.length() > 0) result.append(' ');
            result.append(quotePath(file.getAbsolutePath()));
        }
        return result.toString();
    }

    /** Single-quoted argument compatible with common POSIX shells and fish. */
    public static String quotePath(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }
}
