package org.phoenix.core.network.netbird;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal bounded JSON reader/writer for NetBird's public API payloads and journal codec. */
final class NetBirdJson {
    private static final int MAX_NESTING = 32;
    private static final int MAX_CHARS = 256 * 1024;

    private final String source;
    private int offset;

    private NetBirdJson(String source) { this.source = source; }

    static Object parse(String source) {
        if (source == null || source.length() > MAX_CHARS) throw new IllegalArgumentException("Invalid JSON");
        NetBirdJson parser = new NetBirdJson(source);
        Object value = parser.value(0);
        parser.ws();
        if (parser.offset != source.length()) throw new IllegalArgumentException("Invalid JSON");
        return value;
    }

    static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : null;
    }

    static String string(Map<String, Object> map, String name, boolean required) {
        Object value = map.get(name);
        if (value == null && !required) return null;
        if (!(value instanceof String text) || (required && text.isBlank())) throw new IllegalArgumentException("Invalid " + name);
        return (String) value;
    }

    static int integer(Map<String, Object> map, String name, boolean required, int fallback) {
        Object value = map.get(name);
        if (value == null && !required) return fallback;
        if (!(value instanceof BigDecimal number)) throw new IllegalArgumentException("Invalid " + name);
        try { return number.intValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("Invalid " + name); }
    }

    static long longInteger(Map<String, Object> map, String name, boolean required, long fallback) {
        Object value = map.get(name);
        if (value == null && !required) return fallback;
        if (!(value instanceof BigDecimal number)) throw new IllegalArgumentException("Invalid " + name);
        try { return number.longValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("Invalid " + name); }
    }

    static boolean bool(Map<String, Object> map, String name, boolean fallback) {
        Object value = map.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("Invalid " + name);
        return flag;
    }

    private Object value(int depth) {
        if (depth > MAX_NESTING) throw new IllegalArgumentException("Invalid JSON");
        ws();
        if (offset >= source.length()) throw new IllegalArgumentException("Invalid JSON");
        return switch (source.charAt(offset)) {
            case '{' -> objectValue(depth);
            case '[' -> arrayValue(depth);
            case '"' -> quotedString();
            case 't' -> { literal("true"); yield Boolean.TRUE; }
            case 'f' -> { literal("false"); yield Boolean.FALSE; }
            case 'n' -> { literal("null"); yield null; }
            default -> number();
        };
    }

    private Map<String, Object> objectValue(int depth) {
        offset++;
        ws();
        Map<String, Object> out = new LinkedHashMap<>();
        if (consume('}')) return out;
        while (true) {
            ws();
            if (offset >= source.length() || source.charAt(offset) != '"') throw new IllegalArgumentException("Invalid JSON");
            String key = quotedString();
            ws(); require(':');
            if (out.containsKey(key)) throw new IllegalArgumentException("Invalid JSON");
            out.put(key, value(depth + 1));
            ws();
            if (consume('}')) return out;
            require(',');
        }
    }

    private List<Object> arrayValue(int depth) {
        offset++;
        ws();
        List<Object> out = new ArrayList<>();
        if (consume(']')) return out;
        while (true) {
            out.add(value(depth + 1));
            ws();
            if (consume(']')) return out;
            require(',');
        }
    }

    private String quotedString() {
        require('"');
        StringBuilder out = new StringBuilder();
        while (offset < source.length()) {
            char c = source.charAt(offset++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw new IllegalArgumentException("Invalid JSON");
            if (c != '\\') { out.append(c); continue; }
            if (offset >= source.length()) throw new IllegalArgumentException("Invalid JSON");
            char escaped = source.charAt(offset++);
            switch (escaped) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (offset + 4 > source.length()) throw new IllegalArgumentException("Invalid JSON");
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int digit = Character.digit(source.charAt(offset++), 16);
                        if (digit < 0) throw new IllegalArgumentException("Invalid JSON");
                        code = (code << 4) | digit;
                    }
                    out.append((char) code);
                }
                default -> throw new IllegalArgumentException("Invalid JSON");
            }
        }
        throw new IllegalArgumentException("Invalid JSON");
    }

    private BigDecimal number() {
        int start = offset;
        consume('-');
        if (!consume('0')) {
            digit();
            while (offset < source.length() && Character.isDigit(source.charAt(offset))) offset++;
        } else if (offset < source.length() && Character.isDigit(source.charAt(offset))) {
            throw new IllegalArgumentException("Invalid JSON");
        }
        if (consume('.')) { digit(); while (offset < source.length() && Character.isDigit(source.charAt(offset))) offset++; }
        if (consume('e') || consume('E')) { if (!consume('+')) consume('-'); digit(); while (offset < source.length() && Character.isDigit(source.charAt(offset))) offset++; }
        try { return new BigDecimal(source.substring(start, offset)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid JSON"); }
    }

    private void literal(String text) {
        if (!source.regionMatches(offset, text, 0, text.length())) throw new IllegalArgumentException("Invalid JSON");
        offset += text.length();
    }

    private void digit() {
        if (offset >= source.length() || !Character.isDigit(source.charAt(offset))) throw new IllegalArgumentException("Invalid JSON");
        offset++;
    }

    private void ws() { while (offset < source.length() && Character.isWhitespace(source.charAt(offset))) offset++; }
    private boolean consume(char c) { if (offset < source.length() && source.charAt(offset) == c) { offset++; return true; } return false; }
    private void require(char c) { if (!consume(c)) throw new IllegalArgumentException("Invalid JSON"); }
}
