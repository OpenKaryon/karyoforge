package org.openkaryon.karyoforge.notebook.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.io.Writer;
import java.math.BigDecimal;

/** Strict bounded JSON I/O and checked field projections, independent of IDE services. */
final class NotebookJson {
    private static final int MAX_JSON_DEPTH = 128;
    private static final int MAX_JSON_NODES = 2_000_000;
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();
    private NotebookJson() {}

    static JsonElement read(String source) throws IOException {
        try (var reader = new JsonReader(new StringReader(source))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = readJson(reader, 0, new int[]{0});
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("Unexpected text after the notebook JSON");
            return value;
        }
    }

    static String write(JsonElement value) {
        walk(value, 0, new int[]{0}, false);
        var output = new BoundedWriter();
        JSON.toJson(value, output);
        output.write("\n");
        return output.toString();
    }

    static JsonObject copy(JsonObject value) { return walk(value, 0, new int[]{0}, true).getAsJsonObject(); }
    static JsonArray copy(JsonArray value) { return walk(value, 0, new int[]{0}, true).getAsJsonArray(); }

    private static JsonElement walk(JsonElement value, int depth, int[] nodes, boolean copy) {
        if (depth > MAX_JSON_DEPTH) throw invalid("JSON nesting exceeds " + MAX_JSON_DEPTH + " levels");
        if (++nodes[0] > MAX_JSON_NODES) throw invalid("Notebook JSON exceeds the " + MAX_JSON_NODES + " value limit");
        if (value.isJsonObject()) {
            var result = copy ? new JsonObject() : null;
            for (var entry : value.getAsJsonObject().entrySet()) {
                JsonElement child = walk(entry.getValue(), depth + 1, nodes, copy);
                if (copy) result.add(entry.getKey(), child);
            }
            if (copy) return result;
        } else if (value.isJsonArray()) {
            var result = copy ? new JsonArray() : null;
            for (var child : value.getAsJsonArray()) {
                JsonElement owned = walk(child, depth + 1, nodes, copy);
                if (copy) result.add(owned);
            }
            if (copy) return result;
        } else if (copy && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            // Gson primitives can wrap mutable Number objects supplied by callers.
            return new JsonPrimitive(new LiteralNumber(value.getAsString()));
        }
        return value;
    }

    private static final class BoundedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        @Override public void write(char[] value, int start, int length) {
            check(length);
            text.append(value, start, length);
        }
        @Override public void write(String value) { write(value, 0, value.length()); }
        @Override public void write(String value, int start, int length) {
            check(length);
            text.append(value, start, start + length);
        }
        private void check(int length) {
            if (length > NotebookDocument.MAX_INPUT_CHARACTERS - text.length()) {
                throw invalid("Serialized notebook exceeds the text limit; shorten the edit before saving");
            }
        }
        @Override public void flush() {}
        @Override public void close() {}
        @Override public String toString() {
            int unpaired = 0;
            for (int index = 0; index < text.length(); index++) {
                char value = text.charAt(index);
                if (Character.isHighSurrogate(value) && index + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(index + 1))) index++;
                else if (Character.isSurrogate(value)) unpaired++;
            }
            if (unpaired == 0) return text.toString();
            check(unpaired * 5);
            var safe = new StringBuilder(text.length() + unpaired * 5);
            String hex = "0123456789abcdef";
            for (int index = 0; index < text.length(); index++) {
                char value = text.charAt(index);
                if (Character.isHighSurrogate(value) && index + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(index + 1))) {
                    safe.append(value).append(text.charAt(++index));
                } else if (Character.isSurrogate(value)) {
                    safe.append("\\u").append(hex.charAt(value >>> 12)).append(hex.charAt((value >>> 8) & 15))
                            .append(hex.charAt((value >>> 4) & 15)).append(hex.charAt(value & 15));
                } else safe.append(value);
            }
            return safe.toString();
        }
    }

    static JsonElement readJson(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > MAX_JSON_DEPTH) throw invalid("JSON nesting exceeds " + MAX_JSON_DEPTH + " levels");
        if (++nodes[0] > MAX_JSON_NODES) throw invalid("Notebook JSON exceeds the " + MAX_JSON_NODES + " value limit");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw invalid("Duplicate JSON field: " + name);
                    object.add(name, readJson(reader, depth + 1, nodes));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readJson(reader, depth + 1, nodes));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new LiteralNumber(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw invalid("Expected a JSON value at " + reader.getPath());
        };
    }

    static String multiline(JsonElement value, String label) {
        if (isString(value)) return value.getAsString();
        if (value != null && value.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement part : value.getAsJsonArray()) text.append(string(part, label + " line"));
            return text.toString();
        }
        throw invalid(label + " must be a string or an array of strings");
    }

    static JsonObject object(JsonElement value, String label) {
        if (value == null || !value.isJsonObject()) throw invalid(label + " must be an object");
        return value.getAsJsonObject();
    }

    static JsonArray array(JsonElement value, String label) {
        if (value == null || !value.isJsonArray()) throw invalid(label + " must be an array");
        return value.getAsJsonArray();
    }

    static String string(JsonElement value, String label) {
        if (!isString(value)) throw invalid(label + " must be a string");
        return value.getAsString();
    }

    static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    static Integer integer(JsonElement value, String label, boolean nullable) {
        if (nullable && value != null && value.isJsonNull()) return null;
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(label + " must be a nonnegative integer" + (nullable ? " or null" : ""));
        }
        try {
            int number = value.getAsBigDecimal().intValueExact();
            if (number < 0) throw invalid(label + " must be nonnegative");
            return number;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid(label + " must be an integer between 0 and " + Integer.MAX_VALUE);
        }
    }

    static boolean explicitFalse(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && !value.getAsBoolean();
    }

    static boolean same(JsonElement first, JsonElement second) {
        if (first.isJsonObject() && second.isJsonObject()) {
            var left = first.getAsJsonObject();
            var right = second.getAsJsonObject();
            if (!left.keySet().equals(right.keySet())) return false;
            for (var entry : left.entrySet()) if (!same(entry.getValue(), right.get(entry.getKey()))) return false;
            return true;
        }
        if (first.isJsonArray() && second.isJsonArray()) {
            var left = first.getAsJsonArray();
            var right = second.getAsJsonArray();
            if (left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) if (!same(left.get(index), right.get(index))) return false;
            return true;
        }
        if (first.isJsonPrimitive() && second.isJsonPrimitive()
                && first.getAsJsonPrimitive().isNumber() && second.getAsJsonPrimitive().isNumber()) {
            return first.getAsString().equals(second.getAsString());
        }
        return first.equals(second);
    }

    /** Preserve JSON number spelling, signed zero and precision without internal Gson classes. */
    private static final class LiteralNumber extends Number {
        private final String literal;
        private LiteralNumber(String literal) {
            if (literal.length() > 1024) throw invalid("Numeric literal exceeds the 1024 character limit");
            new BigDecimal(literal); // Reject non-finite values and unsupported exponents.
            this.literal = literal;
        }
        @Override public int intValue() { return new BigDecimal(literal).intValue(); }
        @Override public long longValue() { return new BigDecimal(literal).longValue(); }
        @Override public float floatValue() { return Float.parseFloat(literal); }
        @Override public double doubleValue() { return Double.parseDouble(literal); }
        @Override public String toString() { return literal; }
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
