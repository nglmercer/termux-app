package com.termux.app.terminal.clipboard;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Streams an explicitly selected image while checking its signature and encoded size. */
public final class ImageStreamImporter {
    private ImageStreamImporter() {}

    /** The caller owns and closes the streams and must delete partial output on failure. */
    public static long copy(InputStream input, OutputStream output, String mime, long maxBytes)
        throws IOException {
        if (maxBytes < 12) throw new IllegalArgumentException("maxBytes is too small");
        if (!"image/png".equals(mime) && !"image/jpeg".equals(mime) && !"image/webp".equals(mime))
            throw new ImageImportException(ImageImportException.Reason.UNSUPPORTED);

        byte[] header = new byte[12];
        int read = 0;
        while (read < header.length) {
            int n = input.read(header, read, header.length - read);
            if (n < 0) throw new ImageImportException(ImageImportException.Reason.UNSUPPORTED);
            if (n == 0) {
                int b = input.read();
                if (b < 0) throw new ImageImportException(ImageImportException.Reason.UNSUPPORTED);
                header[read++] = (byte) b;
            } else {
                read += n;
            }
        }
        if (!matches(header, mime)) throw new ImageImportException(ImageImportException.Reason.UNSUPPORTED);
        output.write(header);
        long total = header.length;
        byte[] buffer = new byte[8192];
        for (;;) {
            int n = input.read(buffer);
            if (n < 0) return total;
            if (n == 0) {
                int b = input.read();
                if (b < 0) return total;
                if (total == maxBytes) throw new ImageImportException(ImageImportException.Reason.SIZE);
                output.write(b);
                total++;
                continue;
            }
            if (n > maxBytes - total) throw new ImageImportException(ImageImportException.Reason.SIZE);
            output.write(buffer, 0, n);
            total += n;
        }
    }

    private static boolean matches(byte[] h, String mime) {
        if ("image/png".equals(mime))
            return (h[0] & 255) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && h[4] == 13 && h[5] == 10 && h[6] == 26 && h[7] == 10;
        if ("image/jpeg".equals(mime))
            return (h[0] & 255) == 0xff && (h[1] & 255) == 0xd8 && (h[2] & 255) == 0xff;
        return h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
            && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
    }
}
