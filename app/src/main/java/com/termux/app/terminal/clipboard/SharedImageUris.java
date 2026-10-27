package com.termux.app.terminal.clipboard;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/** Validates an external image share without reading or logging its content. */
public final class SharedImageUris {
    private SharedImageUris() { }

    public static List<Uri> fromIntent(Intent intent) {
        if (intent == null) throw new IllegalArgumentException("Missing share");
        List<Parcelable> values = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<? extends Parcelable> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) values.addAll(streams);
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Parcelable stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) values.add(stream);
        } else {
            throw new IllegalArgumentException("Unsupported share action");
        }
        if (values.isEmpty()) {
            ClipData clip = intent.getClipData();
            if (clip != null) {
                if (clip.getItemCount() > ClipboardImageImporter.MAX_BATCH_ITEMS)
                    throw new IllegalArgumentException("Too many images");
                for (int i = 0; i < clip.getItemCount(); i++) values.add(clip.getItemAt(i).getUri());
            }
        }
        if (values.isEmpty() || values.size() > ClipboardImageImporter.MAX_BATCH_ITEMS)
            throw new IllegalArgumentException("Invalid image count");
        List<Uri> uris = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Uri) || !"content".equals(((Uri) value).getScheme()))
                throw new IllegalArgumentException("Invalid image URI");
            uris.add((Uri) value);
        }
        return uris;
    }
}
