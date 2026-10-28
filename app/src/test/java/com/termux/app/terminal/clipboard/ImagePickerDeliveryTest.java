package com.termux.app.terminal.clipboard;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImagePickerDeliveryTest {
    @Test public void waitsForVisibilityServiceAndAttachment() {
        assertEquals(ImagePickerDelivery.Decision.WAIT, ImagePickerDelivery.decide(false, true, true, true, true, true));
        assertEquals(ImagePickerDelivery.Decision.WAIT, ImagePickerDelivery.decide(true, false, false, false, false, false));
        assertEquals(ImagePickerDelivery.Decision.WAIT, ImagePickerDelivery.decide(true, true, true, false, false, true));
    }
    @Test public void onlyImportsToTheOriginalLiveSession() {
        assertEquals(ImagePickerDelivery.Decision.REJECT, ImagePickerDelivery.decide(true, true, false, true, false, false));
        assertEquals(ImagePickerDelivery.Decision.REJECT, ImagePickerDelivery.decide(true, true, true, true, false, true));
        assertEquals(ImagePickerDelivery.Decision.REJECT, ImagePickerDelivery.decide(true, true, true, true, true, false));
        assertEquals(ImagePickerDelivery.Decision.REJECT, ImagePickerDelivery.decide(true, true, true, false, false, false));
        assertEquals(ImagePickerDelivery.Decision.IMPORT, ImagePickerDelivery.decide(true, true, true, true, true, true));
    }
}
