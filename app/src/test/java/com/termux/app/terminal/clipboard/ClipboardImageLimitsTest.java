package com.termux.app.terminal.clipboard;

import com.termux.shared.termux.settings.properties.ClipboardImageLimits;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.properties.TermuxSharedProperties;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClipboardImageLimitsTest {
    @Test public void parsesLimitsAndUsesDefaultsForInvalidValues() {
        assertEquals(20, ClipboardImageLimits.parseFile(null));
        assertEquals(40, ClipboardImageLimits.parseBatch("bad"));
        assertEquals(100, ClipboardImageLimits.parseCache("999999999999999999999999"));
        assertEquals(1, ClipboardImageLimits.parseFile(" 1 "));
        assertEquals(64, ClipboardImageLimits.parseFile("64"));
        assertEquals(20, ClipboardImageLimits.parseFile("65"));
        assertEquals(40, ClipboardImageLimits.parseBatch("0"));
        assertEquals(128, ClipboardImageLimits.parseBatch("128"));
        assertEquals(100, ClipboardImageLimits.parseCache("-1"));
        assertEquals(256, ClipboardImageLimits.parseCache("256"));
    }
    @Test public void batchBudgetAppliesTightestLimitAndReportsCachePressure() throws Exception {
        ClipboardImageLimits limits = ClipboardImageLimits.of(2, 3, 4);
        long mib = 1024L * 1024;
        ImageImportBudget batch = new ImageImportBudget(limits, 4 * mib);
        assertEquals(2 * mib, batch.nextLimit());
        batch.consume(2 * mib);
        assertEquals(mib, batch.nextLimit());
        assertEquals(ImageImportException.Reason.SIZE, batch.mapSizeFailure(new ImageImportException(ImageImportException.Reason.SIZE)).getReason());
        ImageImportBudget cache = new ImageImportBudget(limits, 2 * mib + 20);
        cache.consume(2 * mib);
        assertEquals(20, cache.nextLimit());
        assertEquals(ImageImportException.Reason.CACHE, cache.mapSizeFailure(new ImageImportException(ImageImportException.Reason.SIZE)).getReason());
        cache.consume(20);
        try { cache.nextLimit(); fail(); } catch (ImageImportException expected) { assertEquals(ImageImportException.Reason.CACHE, expected.getReason()); }
        try { new ImageImportBudget(limits, 11); fail(); } catch (ImageImportException expected) { assertEquals(ImageImportException.Reason.CACHE, expected.getReason()); }
    }
    @Test public void propertiesParserAndByteConversionUseValidatedValues() {
        String file = TermuxPropertyConstants.KEY_CLIPBOARD_IMAGE_MAX_FILE_MIB;
        String batch = TermuxPropertyConstants.KEY_CLIPBOARD_IMAGE_MAX_BATCH_MIB;
        String cache = TermuxPropertyConstants.KEY_CLIPBOARD_IMAGE_MAX_CACHE_MIB;
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(file));
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(batch));
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(cache));
        assertEquals(3, TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, file, "3"));
        assertEquals(40, TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, batch, "0"));
        assertEquals(256, TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, cache, "256"));
        ClipboardImageLimits limits = ClipboardImageLimits.of(3, 2, 1);
        assertEquals(3L * 1024 * 1024, limits.fileBytes());
        assertEquals(2L * 1024 * 1024, limits.batchBytes());
        assertEquals(1L * 1024 * 1024, limits.cacheBytes());
        try { ClipboardImageLimits.of(0, 40, 100); fail(); } catch (IllegalArgumentException expected) { }
    }
}
