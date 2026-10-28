package com.termux.app.terminal.clipboard;

import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ImagePreviewInstrumentedTest {
    @Test public void decodesBoundedThumbnailAndDeclinesMalformedFile() throws Exception {
        File file = File.createTempFile("preview-test-", ".png",
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        Bitmap source = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888);
        try {
            try (FileOutputStream out = new FileOutputStream(file)) {
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
            Bitmap preview = ImagePreview.decode(file);
            assertNotNull(preview);
            try {
                assertTrue(preview.getWidth() <= 128);
                assertTrue(preview.getHeight() <= 128);
                assertTrue(preview.getAllocationByteCount() <= 2 * 1024 * 1024);
            } finally { preview.recycle(); }
            for (int i = 0; i < 50; i++) {
                Bitmap repeated = ImagePreview.decode(file);
                assertNotNull(repeated);
                repeated.recycle();
            }
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[] {1, 2, 3}); }
            assertNull(ImagePreview.decode(file));
        } finally { source.recycle(); assertTrue(file.delete()); }
    }
}
