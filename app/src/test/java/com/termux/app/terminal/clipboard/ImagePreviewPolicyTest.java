package com.termux.app.terminal.clipboard;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImagePreviewPolicyTest {
    @Test public void usesPowerOfTwoSamplesToBoundBothDimensions() {
        assertEquals(1, ImagePreviewPolicy.sampleSize(128, 128));
        assertEquals(2, ImagePreviewPolicy.sampleSize(129, 1));
        assertEquals(32, ImagePreviewPolicy.sampleSize(3840, 2160));
        assertEquals(1024, ImagePreviewPolicy.sampleSize(100000, 1));
    }
    @Test public void rejectsInvalidAndOversizedGeometryWithoutOverflow() {
        assertEquals(0, ImagePreviewPolicy.sampleSize(0, 100));
        assertEquals(0, ImagePreviewPolicy.sampleSize(-1, 100));
        assertEquals(0, ImagePreviewPolicy.sampleSize(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(0, ImagePreviewPolicy.sampleSize(10001, 10000));
        assertTrue(ImagePreviewPolicy.sampleSize(10000, 10000) > 0);
    }
}
