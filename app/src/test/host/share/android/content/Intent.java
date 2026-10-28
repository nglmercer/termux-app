package android.content;
import android.os.Parcelable;
import java.util.ArrayList;
public final class Intent {
    public static final String ACTION_SEND = "android.intent.action.SEND";
    public static final String ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE";
    public static final String EXTRA_STREAM = "android.intent.extra.STREAM";
    private final String action;
    private Parcelable stream;
    private ArrayList<? extends Parcelable> streams;
    private ClipData clip;
    public Intent(String action) { this.action = action; }
    public String getAction() { return action; }
    @SuppressWarnings("unchecked") public <T extends Parcelable> T getParcelableExtra(String key) { return (T) stream; }
    @SuppressWarnings("unchecked") public <T extends Parcelable> ArrayList<T> getParcelableArrayListExtra(String key) { return (ArrayList<T>) streams; }
    public ClipData getClipData() { return clip; }
    public Intent stream(Parcelable value) { stream = value; return this; }
    public Intent streams(ArrayList<? extends Parcelable> value) { streams = value; return this; }
    public Intent clip(ClipData value) { clip = value; return this; }
}
