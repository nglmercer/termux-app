package com.termux.app.terminal.clipboard;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SharedImageUrisHostTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    private static void rejected(Intent intent) {
        try { SharedImageUris.fromIntent(intent); throw new AssertionError("Expected rejection"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void rejectedPicker(Intent intent) {
        try { PickerImageSelection.fromIntent(intent); throw new AssertionError("Expected picker rejection"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        Uri a = Uri.parse("content://provider/a"), b = Uri.parse("content://provider/b");
        check(SharedImageUris.fromIntent(new Intent(Intent.ACTION_SEND).stream(a)).get(0) == a);
        ArrayList<Uri> batch = new ArrayList<>(Arrays.asList(a, b));
        List<Uri> parsed = SharedImageUris.fromIntent(new Intent(Intent.ACTION_SEND_MULTIPLE).streams(batch));
        check(parsed.size() == 2 && parsed.get(0) == a && parsed.get(1) == b);
        check(SharedImageUris.fromIntent(new Intent(Intent.ACTION_SEND).clip(new ClipData(batch))).size() == 2);
        rejected(new Intent(Intent.ACTION_SEND));
        rejected(new Intent("other").stream(a));
        rejected(new Intent(Intent.ACTION_SEND).stream(new Parcelable() { }));
        rejected(new Intent(Intent.ACTION_SEND).stream(Uri.parse("file:///image")));
        batch.clear();
        for (int i = 0; i < 9; i++) batch.add(a);
        rejected(new Intent(Intent.ACTION_SEND_MULTIPLE).streams(batch));
        rejected(new Intent(Intent.ACTION_SEND).clip(new ClipData(batch)));
        check(PickerImageSelection.fromIntent(new Intent("picker").data(a)).get(0) == a);
        check(PickerImageSelection.fromIntent(new Intent("picker").clip(new ClipData(Arrays.asList(a, b)))).size() == 2);
        rejectedPicker(new Intent("picker"));
        rejectedPicker(new Intent("picker").data(Uri.parse("file:///image")));
        rejectedPicker(new Intent("picker").clip(new ClipData(batch)));
        StringBuilder longUri = new StringBuilder("content://provider/");
        while (longUri.length() <= 4096) longUri.append('x');
        rejectedPicker(new Intent("picker").data(Uri.parse(longUri.toString())));
        System.out.println("Shared and picker image URI validation passed (host Android stubs)");
    }
}
