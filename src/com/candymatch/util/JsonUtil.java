package com.candymatch.util;

import java.util.*;

/**
 * Lightweight, zero-dependency JSON parser and serializer supporting
 * objects (LinkedHashMap), arrays (ArrayList), strings (with full UTF-8 / surrogate support),
 * numbers (Long/Double), booleans, and null.
 */
public final class JsonUtil {

    private JsonUtil() {}

    public static String stringify(Object value) {
        return stringify(value, false);
    }

    public static String stringifyPretty(Object value) {
        return stringify(value, true);
    }

    public static String stringify(Object value, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, pretty, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, boolean pretty, int depth) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            sb.append(value);
        } else if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                sb.append("null");
            } else {
                sb.append(d);
            }
        } else if (value instanceof Float f) {
            if (f.isNaN() || f.isInfinite()) {
                sb.append("null");
            } else {
                sb.append(f);
            }
        } else if (value instanceof Number n) {
            sb.append(n);
        } else if (value instanceof Map<?, ?> map) {
            writeMap(sb, map, pretty, depth);
        } else if (value instanceof Collection<?> col) {
            writeCollection(sb, col, pretty, depth);
        } else if (value instanceof int[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
        } else if (value instanceof int[][] grid) {
            sb.append('[');
            for (int i = 0; i < grid.length; i++) {
                if (i > 0) sb.append(',');
                writeValue(sb, grid[i], false, depth + 1);
            }
            sb.append(']');
        } else if (value instanceof Object[] arr) {
            writeCollection(sb, Arrays.asList(arr), pretty, depth);
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map, boolean pretty, int depth) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            if (pretty) {
                sb.append('\n');
                indent(sb, depth + 1);
            }
            writeString(sb, String.valueOf(entry.getKey()));
            sb.append(pretty ? ": " : ":");
            writeValue(sb, entry.getValue(), pretty, depth + 1);
        }
        if (pretty && !map.isEmpty()) {
            sb.append('\n');
            indent(sb, depth);
        }
        sb.append('}');
    }

    private static void writeCollection(StringBuilder sb, Collection<?> col, boolean pretty, int depth) {
        sb.append('[');
        boolean first = true;
        for (Object item : col) {
            if (!first) sb.append(',');
            first = false;
            if (pretty) {
                sb.append('\n');
                indent(sb, depth + 1);
            }
            writeValue(sb, item, pretty, depth + 1);
        }
        if (pretty && !col.isEmpty()) {
            sb.append('\n');
            indent(sb, depth);
        }
        sb.append(']');
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static Object parse(String json) {
        if (json == null) return null;
        Parser parser = new Parser(json);
        Object val = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.eof()) {
            throw new IllegalArgumentException("Unexpected trailing JSON content at index " + parser.pos);
        }
        return val;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object o = parse(json);
        if (o == null) return new LinkedHashMap<>();
        if (!(o instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) o;
    }

    public static String asString(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    public static int asInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    public static Integer asIntOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return n.intValue();
        }
        if (o instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) return null;
            try { return Integer.parseInt(t); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    public static boolean asBool(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        if (o instanceof List<?> l) return (List<Object>) l;
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Collections.emptyMap();
    }

    public static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean eof() {
            return pos >= src.length();
        }

        void skipWhitespace() {
            while (!eof()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
                else break;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (eof()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield parseNumber();
                    throw new IllegalArgumentException("Unexpected character '" + c + "' at index " + pos);
                }
            };
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (!eof() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (eof()) throw new IllegalArgumentException("Unterminated JSON object");
                char c = src.charAt(pos++);
                if (c == '}') break;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or '}' at index " + (pos - 1));
            }
            return map;
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (!eof() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (eof()) throw new IllegalArgumentException("Unterminated JSON array");
                char c = src.charAt(pos++);
                if (c == ']') break;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or ']' at index " + (pos - 1));
            }
            return list;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (!eof()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (eof()) throw new IllegalArgumentException("Unterminated escape sequence");
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) throw new IllegalArgumentException("Invalid unicode escape");
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("Invalid escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        Object parseLiteral(String literal, Object value) {
            if (src.startsWith(literal, pos)) {
                pos += literal.length();
                return value;
            }
            throw new IllegalArgumentException("Expected '" + literal + "' at index " + pos);
        }

        Number parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') pos++;
            while (!eof() && Character.isDigit(src.charAt(pos))) pos++;
            boolean isFloat = false;
            if (!eof() && src.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                while (!eof() && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (!eof() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (!eof() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (!eof() && Character.isDigit(src.charAt(pos))) pos++;
            }
            String numStr = src.substring(start, pos);
            if (isFloat) {
                return Double.parseDouble(numStr);
            }
            long l = Long.parseLong(numStr);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return (int) l;
            }
            return l;
        }

        void expect(char expected) {
            if (eof() || src.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at index " + pos);
            }
            pos++;
        }
    }
}
