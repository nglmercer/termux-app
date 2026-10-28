package com.termux.app.extrakeys;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/** Non-destructive editor model for the existing extra-keys array syntax. */
public final class ExtraKeyLayout {
    public static final int MAX_ROWS = 3, MAX_KEYS_PER_ROW = 24;
    private static final int MAX_INPUT = 65536, MAX_DEPTH = 8, MAX_VALUES = 2048, MAX_STRING = 4096;
    private final JsonArray rows;

    private ExtraKeyLayout(JsonArray rows) { this.rows = rows; }

    public enum Preset { CLASSIC, CODING, VIM, TMUX, COMPACT }

    /** Presets use the existing extra-key names; custom layouts remain independent. */
    public static ExtraKeyLayout preset(Preset preset) throws IOException {
        if (preset == null) throw new IllegalArgumentException("Preset required");
        switch (preset) {
            case CLASSIC: return parse("[['ESC','/',{key:'-',popup:'|'},'HOME','UP','END','PGUP'],['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]");
            case CODING: return parse("[['ESC','TAB','CTRL','ALT','LEFT','DOWN','UP','RIGHT'],['HOME','END','PGUP','PGDN','/','-','|','PASTE']]");
            case VIM: return parse("[['ESC','CTRL','ALT','LEFT','DOWN','UP','RIGHT'],['TAB',':','/','HOME','END','PGUP','PGDN']]");
            case TMUX: return parse("[['ESC','TAB','CTRL','ALT','LEFT','DOWN','UP','RIGHT'],['HOME','END','PGUP','PGDN','|','-','PASTE']]");
            case COMPACT: return parse("[['ESC','TAB','CTRL','ALT','LEFT','DOWN','UP','RIGHT']]");
            default: throw new IllegalArgumentException("Unknown preset");
        }
    }

    /** Accepts upstream's JSON-like single quotes and unquoted keys, but rejects duplicate fields. */
    public static ExtraKeyLayout parse(String input) throws IOException {
        if (input == null || input.length() > MAX_INPUT) throw new IOException("Invalid layout size");
        try (JsonReader reader = new JsonReader(new StringReader(input))) {
            reader.setStrictness(Strictness.LENIENT);
            JsonElement root = read(reader, 0, new int[]{0});
            if (reader.peek() != JsonToken.END_DOCUMENT || !root.isJsonArray()) throw new IOException("Expected layout array");
            JsonArray rows = root.getAsJsonArray();
            if (rows.size() < 1 || rows.size() > MAX_ROWS) throw new IOException("Expected one to three rows");
            for (JsonElement row : rows) {
                if (!row.isJsonArray() || row.getAsJsonArray().size() > MAX_KEYS_PER_ROW) throw new IOException("Invalid row");
                for (JsonElement key : row.getAsJsonArray()) validateKey(key);
            }
            if (rows.toString().length() > MAX_INPUT) throw new IOException("Serialized layout is too large");
            return new ExtraKeyLayout(rows);
        } catch (IllegalStateException | NumberFormatException e) {
            throw new IOException("Invalid layout", e);
        }
    }

    private static JsonElement read(JsonReader reader, int depth, int[] count) throws IOException {
        if (depth > MAX_DEPTH || ++count[0] > MAX_VALUES) throw new IOException("Layout is too complex");
        switch (reader.peek()) {
            case BEGIN_ARRAY: {
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1, count));
                reader.endArray(); return array;
            }
            case BEGIN_OBJECT: {
                JsonObject object = new JsonObject(); Set<String> names = new HashSet<>(); reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.length() > MAX_STRING || !names.add(name)) throw new IOException("Duplicate or oversized field");
                    object.add(name, read(reader, depth + 1, count));
                }
                reader.endObject(); return object;
            }
            case STRING: {
                String text = reader.nextString();
                if (text.length() > MAX_STRING) throw new IOException("Value too long");
                return new JsonPrimitive(text);
            }
            case NUMBER: {
                String text = reader.nextString();
                if (text.length() > MAX_STRING) throw new IOException("Number too long");
                return new JsonPrimitive(new BigDecimal(text));
            }
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw new IOException("Invalid layout value");
        }
    }

    private static void validateKey(JsonElement key) throws IOException {
        if (key == null || key.isJsonNull()) throw new IOException("Missing key");
        if (key.isJsonPrimitive() && key.getAsJsonPrimitive().isString()) return;
        if (!key.isJsonObject()) throw new IOException("Key must be a string or object");
        JsonObject object = key.getAsJsonObject();
        if ((object.has("key") && !stringField(object, "key")) ||
            (object.has("macro") && !stringField(object, "macro"))) throw new IOException("Invalid key value");
        boolean simple = stringField(object, "key"), macro = stringField(object, "macro");
        if (simple == macro) throw new IOException("Specify exactly one key or macro");
        if (object.has("display") && !stringField(object, "display")) throw new IOException("Invalid display");
        if (object.has("popup")) {
            JsonElement popup = object.get("popup");
            if (popup.isJsonPrimitive() && popup.getAsJsonPrimitive().isString()) return;
            if (!popup.isJsonObject()) throw new IOException("Invalid popup");
            JsonObject pop = popup.getAsJsonObject();
            if ((pop.has("key") && !stringField(pop, "key")) || (pop.has("macro") && !stringField(pop, "macro")))
                throw new IOException("Invalid popup key");
            if (stringField(pop, "key") == stringField(pop, "macro")) throw new IOException("Invalid popup key");
            if (pop.has("display") && !stringField(pop, "display")) throw new IOException("Invalid popup display");
        }
    }

    private static boolean stringField(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    public int rowCount() { return rows.size(); }
    public int keyCount(int row) { return row(row).size(); }
    /** Return a copy so callers cannot alter unvalidated configuration. */
    public JsonElement key(int row, int index) { return row(row).get(index).deepCopy(); }
    public String toJson() { return rows.toString(); }

    public void addRow() {
        if (rows.size() >= MAX_ROWS) throw new IllegalStateException("Row limit");
        rows.add(new JsonArray());
        try { parse(rows.toString()); }
        catch (IOException e) { rows.remove(rows.size() - 1); throw new IllegalStateException("Layout limit", e); }
    }
    public void removeRow(int row) {
        if (rows.size() <= 1) throw new IllegalStateException("At least one row is required");
        rows.remove(row);
    }
    public void addKey(int row, String key) {
        if (key == null || key.length() > MAX_STRING) throw new IllegalArgumentException("Invalid key");
        JsonArray target = row(row);
        if (target.size() >= MAX_KEYS_PER_ROW) throw new IllegalStateException("Key limit");
        target.add(key);
        try { parse(rows.toString()); }
        catch (IOException e) { target.remove(target.size() - 1); throw new IllegalStateException("Layout limit", e); }
    }
    /** Explicitly replace one key from JSON while preserving the rest of the layout. */
    public void replaceKeyJson(int row, int index, String keyJson) throws IOException {
        ExtraKeyLayout parsed = parse("[[" + keyJson + "]]");
        if (parsed.keyCount(0) != 1) throw new IOException("Expected one key");
        JsonArray target = row(row);
        JsonElement previous = target.set(index, parsed.key(0, 0));
        try { parse(rows.toString()); }
        catch (IOException e) { target.set(index, previous); throw e; }
    }
    public void removeKey(int row, int index) { row(row).remove(index); }
    /** Destination is an insertion position after the source element is removed. */
    public void moveKey(int fromRow, int fromIndex, int toRow, int toIndex) {
        JsonArray source = row(fromRow), target = row(toRow);
        if (fromIndex < 0 || fromIndex >= source.size() || toIndex < 0 ||
            toIndex > target.size() - (source == target ? 1 : 0)) throw new IndexOutOfBoundsException();
        if (source != target && target.size() >= MAX_KEYS_PER_ROW) throw new IllegalStateException("Key limit");
        JsonElement value = source.remove(fromIndex);
        target.asList().add(toIndex, value);
    }
    private JsonArray row(int index) { return rows.get(index).getAsJsonArray(); }
}
