package com.termux.app.terminal.clipboard;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import java.util.ArrayList;
import java.util.List;

/** Bounded content-URI selection from the system document picker. */
public final class PickerImageSelection {
    private static final int MAX_URI_CHARS = 4096;
    private PickerImageSelection() { }
    public static final class TooManyException extends IllegalArgumentException { }
    public static ArrayList<Uri> fromIntent(Intent intent) {
        if (intent == null) throw new IllegalArgumentException("Missing picker result");
        ArrayList<Uri> values = new ArrayList<>();
        ClipData clip = intent.getClipData();
        if (clip != null) {
            if (clip.getItemCount() > ClipboardImageImporter.MAX_BATCH_ITEMS) throw new TooManyException();
            for (int i = 0; i < clip.getItemCount(); i++) {
                ClipData.Item item = clip.getItemAt(i);
                values.add(item == null ? null : item.getUri());
            }
        } else if (intent.getData() != null) values.add(intent.getData());
        return validate(values);
    }
    public static ArrayList<Uri> validate(List<Uri> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("Missing selection");
        if (values.size() > ClipboardImageImporter.MAX_BATCH_ITEMS) throw new TooManyException();
        ArrayList<Uri> result = new ArrayList<>(values.size());
        for (Uri uri : values) {
            if (uri == null || !"content".equals(uri.getScheme()) || uri.toString().length() > MAX_URI_CHARS)
                throw new IllegalArgumentException("Invalid selection");
            result.add(uri);
        }
        return result;
    }
}
