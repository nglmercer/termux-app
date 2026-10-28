package com.termux.app.extrakeys;

import java.io.IOException;
import java.util.Arrays;

/** Validates a restored preview against the baseline captured when editing began. */
public final class ExtraKeyRecovery {
    public final ExtraKeyLayout layout;
    public final String original;
    public final boolean saveBlocked;

    private ExtraKeyRecovery(ExtraKeyLayout layout, String original, boolean saveBlocked) {
        this.layout = layout; this.original = original; this.saveBlocked = saveBlocked;
    }

    public static ExtraKeyRecovery restore(String edited, String original, byte[] expected, byte[] actual, boolean blocked) throws IOException {
        ExtraKeyLayout layout = ExtraKeyLayout.parse(edited);
        ExtraKeyLayout.parse(original);
        // An absent or malformed fingerprint is never evidence that the baseline is unchanged.
        boolean same = expected != null && expected.length == 32 && actual != null && actual.length == 32 && Arrays.equals(expected, actual);
        return new ExtraKeyRecovery(layout, original, blocked || !same);
    }
}
