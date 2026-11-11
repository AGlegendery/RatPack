package com.darkbiit.rat;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal JSON encoder/decoder.
 *
 * The payload must not depend on org.json (the victim app may ship a different
 * version and the class ids would collide in the merged APK) nor on Gson.
 * This implementation covers what the protocol needs: objects, arrays, strings,
 * numbers, booleans and null.
 */
public final class RatJson {

    private RatJson() {
    }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    public static String encode(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            sb.append(value.toString());
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(value.toString());
            }
        } else if (value instanceof Map) {
            sb.append('{');
            Iterator<Map.Entry<String, Object>> it = ((Map<String, Object>) value).entrySet().iterator();
            boolean first = true;
            while (it.hasNext()) {
                Map.Entry<String, Object> e = it.next();
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<Object>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (value instanceof Object[]) {
            sb.append('[');
            Object[] arr = (Object[]) value;
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, arr[i]);
            }
            sb.append(']');
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    public static Object decode(String json) throws JsonException {
        Parser p = new Parser(json);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos < p.src.length()) {
            throw new JsonException("trailing data at " + p.pos);
        }
        return v;
    }

    /** Decodes into a map, or returns an empty map when the payload is not an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> decodeObject(String json) throws JsonException {
        Object v = decode(json);
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        return new LinkedHashMap<String, Object>();
    }

    public static String getString(Map<String, Object> map, String key, String def) {
        Object o = map == null ? null : map.get(key);
        return o == null ? def : o.toString();
    }

    public static int getInt(Map<String, Object> map, String key, int def) {
        Object o = map == null ? null : map.get(key);
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString().trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static boolean getBool(Map<String, Object> map, String key, boolean def) {
        Object o = map == null ? null : map.get(key);
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o != null) {
            String s = o.toString().trim();
            if (s.equals("true") || s.equals("1")) {
                return true;
            }
            if (s.equals("false") || s.equals("0")) {
                return false;
            }
        }
        return def;
    }

    /** Thrown on malformed JSON. */
    public static class JsonException extends Exception {
        public JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src == null ? "" : src;
        }

        void skipWs() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value() throws JsonException {
            skipWs();
            if (pos >= src.length()) {
                throw new JsonException("unexpected end of input");
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        Map<String, Object> object() throws JsonException {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            pos++; // '{'
            skipWs();
            if (pos < src.length() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (pos >= src.length() || src.charAt(pos) != '"') {
                    throw new JsonException("expected object key at " + pos);
                }
                String key = string();
                skipWs();
                if (pos >= src.length() || src.charAt(pos) != ':') {
                    throw new JsonException("expected ':' at " + pos);
                }
                pos++;
                map.put(key, value());
                skipWs();
                if (pos >= src.length()) {
                    throw new JsonException("unterminated object");
                }
                char c = src.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw new JsonException("expected ',' or '}' at " + pos);
            }
        }

        Object[] array() throws JsonException {
            java.util.List<Object> list = new java.util.ArrayList<Object>();
            pos++; // '['
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
                return list.toArray();
            }
            while (true) {
                list.add(value());
                skipWs();
                if (pos >= src.length()) {
                    throw new JsonException("unterminated array");
                }
                char c = src.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list.toArray();
                }
                throw new JsonException("expected ',' or ']' at " + pos);
            }
        }

        String string() throws JsonException {
            StringBuilder sb = new StringBuilder();
            pos++; // opening quote
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        break;
                    }
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'u':
                            if (pos + 4 > src.length()) {
                                throw new JsonException("bad \\u escape");
                            }
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            throw new JsonException("bad escape: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new JsonException("unterminated string");
        }

        Object number() throws JsonException {
            int start = pos;
            if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) {
                pos++;
            }
            boolean isDouble = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isDouble = true;
                    pos++;
                } else {
                    break;
                }
            }
            String num = src.substring(start, pos);
            if (num.isEmpty()) {
                throw new JsonException("expected value at " + start);
            }
            try {
                if (isDouble) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new JsonException("bad number: " + num);
            }
        }

        void expect(String lit) throws JsonException {
            if (!src.startsWith(lit, pos)) {
                throw new JsonException("expected " + lit + " at " + pos);
            }
            pos += lit.length();
        }
    }
}
