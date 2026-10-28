package com.termux.app.extrakeys;

import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.UUID;

/** Append-only properties edits. All methods perform disk I/O and must run on a worker. */
public final class ExtraKeyPropertyStore {
    public static final class Snapshot {
        private final File file;
        private final byte[] bytes;
        private final boolean existed;
        private final long device, inode;
        private Snapshot(File file, byte[] bytes, boolean existed, long device, long inode) {
            this.file = file; this.bytes = bytes; this.existed = existed; this.device = device; this.inode = inode;
        }
        public File file() { return file; }
        public boolean existed() { return existed; }
        public byte[] bytes() { return bytes.clone(); }
    }
    public static final class SaveResult {
        public final File backup;
        SaveResult(File backup) { this.backup = backup; }
    }
    public static final class ConflictException extends IOException {
        ConflictException() { super("Properties changed since the editor opened"); }
    }
    /** A write may have reached disk; check the target before retrying. */
    public static final class SaveUncertainException extends IOException {
        public final File backup;
        SaveUncertainException(String message, File backup, Throwable cause) {
            super(message, cause); this.backup = backup;
        }
    }
    private ExtraKeyPropertyStore() { }

    /** The caller must choose the same first-readable file used by Termux, or its primary path. */
    public static Snapshot read(File file) throws IOException {
        if (file == null || file.getParentFile() == null) throw new IOException("Invalid properties path");
        FileDescriptor fd = null;
        try {
            fd = Os.open(file.getAbsolutePath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
            StructStat stat = checkedStat(fd);
            checkPath(file, stat);
            byte[] contents = readAll(fd);
            if (!same(stat, checkedStat(fd))) throw new ConflictException();
            checkPath(file, stat);
            return new Snapshot(file, contents, true, stat.st_dev, stat.st_ino);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT && fd == null && file.getParentFile().isDirectory())
                return new Snapshot(file, new byte[0], false, 0, 0);
            throw new IOException("Cannot read properties file", e);
        } finally { close(fd); }
    }

    /** Append a complete property after backing up the original; never truncate the original. */
    public static SaveResult save(Snapshot snapshot, ExtraKeyLayout layout) throws IOException {
        if (snapshot == null) throw new IOException("Missing snapshot");
        byte[] append = ExtraKeyPropertyAppend.prepare(snapshot.bytes, layout);
        FileDescriptor fd = null;
        File backup = null;
        try {
            int flags = OsConstants.O_RDWR | OsConstants.O_APPEND | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC;
            if (!snapshot.existed) flags |= OsConstants.O_CREAT | OsConstants.O_EXCL;
            try { fd = Os.open(snapshot.file.getAbsolutePath(), flags, 0600); }
            catch (ErrnoException e) {
                if ((!snapshot.existed && e.errno == OsConstants.EEXIST) || (snapshot.existed && e.errno == OsConstants.ENOENT))
                    throw new ConflictException();
                throw e;
            }
            StructStat stat = checkedStat(fd);
            if (snapshot.existed && (stat.st_dev != snapshot.device || stat.st_ino != snapshot.inode)) throw new ConflictException();
            checkPath(snapshot.file, stat);
            if (!Arrays.equals(snapshot.bytes, readAll(fd))) throw new ConflictException();
            if (snapshot.existed) backup = writeBackup(snapshot.file, snapshot.bytes);
            // Check again after backup; append never overwrites a concurrent writer's bytes.
            checkPath(snapshot.file, stat);
            if (!same(stat, checkedStat(fd)) || !Arrays.equals(snapshot.bytes, readAll(fd))) throw new ConflictException();
            final int written;
            try { written = Os.write(fd, append, 0, append.length); }
            catch (ErrnoException | InterruptedIOException e) {
                throw new SaveUncertainException("Append may have been partial; inspect the file before retrying", backup, e);
            }
            if (written != append.length) throw new SaveUncertainException("Incomplete append; inspect the file before retrying", backup, null);
            try { Os.fsync(fd); checkPath(snapshot.file, stat); }
            catch (ErrnoException | IOException e) {
                throw new SaveUncertainException("Append completed but sync or path verification failed", backup, e);
            }
            return new SaveResult(backup);
        } catch (ErrnoException | InterruptedIOException e) {
            throw new IOException("Properties save failed; check the file before retrying", e);
        } finally { close(fd); }
    }

    private static File writeBackup(File file, byte[] bytes) throws IOException {
        File backup = new File(file.getParentFile(), file.getName() + ".extra-keys-" + UUID.randomUUID() + ".bak");
        FileDescriptor fd = null;
        try {
            fd = Os.open(backup.getAbsolutePath(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL |
                OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0600);
            int offset = 0;
            while (offset < bytes.length) {
                int count = Os.write(fd, bytes, offset, bytes.length - offset);
                if (count <= 0) throw new IOException("Cannot write backup");
                offset += count;
            }
            Os.fsync(fd);
            return backup;
        } catch (ErrnoException | InterruptedIOException e) { throw new IOException("Cannot create backup; an incomplete backup may remain", e); }
        finally { close(fd); }
    }

    private static byte[] readAll(FileDescriptor fd) throws IOException, ErrnoException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long offset = 0;
        for (;;) {
            int count = Os.pread(fd, chunk, 0, chunk.length, offset);
            if (count == 0) return out.toByteArray();
            if (count < 0 || count > ExtraKeyPropertyAppend.MAX_FILE_BYTES - out.size()) throw new IOException("Properties file is too large");
            out.write(chunk, 0, count); offset += count;
        }
    }

    private static StructStat checkedStat(FileDescriptor fd) throws ErrnoException, IOException {
        StructStat stat = Os.fstat(fd);
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != Process.myUid() || stat.st_nlink != 1)
            throw new IOException("Properties file is not a private regular file");
        return stat;
    }
    private static void checkPath(File file, StructStat stat) throws ErrnoException, IOException {
        if (!same(stat, Os.lstat(file.getAbsolutePath()))) throw new ConflictException();
    }
    private static boolean same(StructStat a, StructStat b) { return a.st_dev == b.st_dev && a.st_ino == b.st_ino; }
    private static void close(FileDescriptor fd) { if (fd != null) try { Os.close(fd); } catch (ErrnoException ignored) { } }
}
