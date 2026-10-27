package android.net;
import android.os.Parcelable;
public final class Uri implements Parcelable {
    private final String value;
    private Uri(String value) { this.value = value; }
    public static Uri parse(String value) { return new Uri(value); }
    public String getScheme() { int i = value.indexOf(':'); return i < 0 ? null : value.substring(0, i); }
}
