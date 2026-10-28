package com.termux.app.terminal.clipboard;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;

/** Decode optional, bounded thumbnail off the UI thread. */
public final class ImagePreview {
    private static final int MAX_ALLOCATION_BYTES = 2 * 1024 * 1024;
    private ImagePreview() { }

    public static Bitmap decode(File file) {
        if (file == null) return null;
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = ImagePreviewPolicy.sampleSize(bounds.outWidth, bounds.outHeight);
            if (sample == 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap == null) return null;
            if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0 || bitmap.getWidth() > 256 ||
                bitmap.getHeight() > 256 || bitmap.getAllocationByteCount() > MAX_ALLOCATION_BYTES) {
                bitmap.recycle(); return null;
            }
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError e) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            return null;
        }
    }
}
