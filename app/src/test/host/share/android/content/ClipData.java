package android.content;
import android.net.Uri;
import java.util.List;
public final class ClipData {
    private final List<Uri> items;
    public ClipData(List<Uri> items) { this.items = items; }
    public int getItemCount() { return items.size(); }
    public Item getItemAt(int index) { return new Item(items.get(index)); }
    public static final class Item {
        private final Uri uri;
        Item(Uri uri) { this.uri = uri; }
        public Uri getUri() { return uri; }
    }
}
