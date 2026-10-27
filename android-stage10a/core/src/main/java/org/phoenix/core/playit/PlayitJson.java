package org.phoenix.core.playit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal strict JSON parser used to keep the core module dependency-free. */
final class PlayitJson {
    private static final int MAX_NESTING = 64;

    private final String source;
    private int offset;

    private PlayitJson(String source) {
        this.source = source;
    }

    static Object parse(String source) {
        PlayitJson parser = new PlayitJson(source);
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.offset != source.length()) {
            throw new IllegalArgumentException("Invalid JSON");
        }
        return value;
    }

    private Object readValue(int depth) {
        if (depth > MAX_NESTING) {
            throw new IllegalArgumentException("Invalid JSON");
        }
        skipWhitespace();
        if (offset >= source.length()) {
            throw new IllegalArgumentException("Invalid JSON");
        }
        char next = source.charAt(offset);
        if (next == '{') {
            return readObject(depth);
        }
        if (next == '[') {
            return readArray(depth);
        }
        if (next == '"') {
            return readString();
        }
        if (next == 't') {
            readLiteral("true");
            return Boolean.TRUE;
        }
        if (next == 'f') {
            readLiteral("false");
            return Boolean.FALSE;
        }
        if (next == 'n') {
            readLiteral("null");
            return null;
        }
        if (next == '-' || (next >= '0' && next <= '9')) {
            return readNumber();
        }
        throw new IllegalArgumentException("Invalid JSON");
    }

    private Map<String, Object> readObject(int depth) {
        offset++;
        skipWhitespace();
        Map<String, Object> object = new LinkedHashMap<>();
        if (consume('}')) {
            return object;
        }
        while (true) {
            skipWhitespace();
            if (offset >= source.length() || source.charAt(offset) != '"') {
                throw new IllegalArgumentException("Invalid JSON");
            }
            String key = readString();
            skipWhitespace();
            require(':');
            Object value = readValue(depth + 1);
            if (object.containsKey(key)) {
                throw new IllegalArgumentException("Invalid JSON");
            }
            object.put(key, value);
            skipWhitespace();
            if (consume('}')) {
                return object;
            }
            require(',');
        }
    }

    private List<Object> readArray(int depth) {
        offset++;
        skipWhitespace();
        List<Object> array = new ArrayList<>();
        if (consume(']')) {
            return array;
        }
        while (true) {
            array.add(readValue(depth + 1));
            skipWhitespace();
            if (consume(']')) {
                return array;
            }
            require(',');
        }
    }

    private String readString() {
        require('"');
        StringBuilder value = new StringBuilder();
        while (offset < source.length()) {
            char next = source.charAt(offset++);
            if (next == '"') {
                return value.toString();
            }
            if (next < 0x20) {
                throw new IllegalArgumentException("Invalid JSON");
            }
            if (next != '\\') {
                value.append(next);
                continue;
            }
            if (offset >= source.length()) {
                throw new IllegalArgumentException("Invalid JSON");
            }
            char escaped = source.charAt(offset++);
            switch (escaped) {
                case '"': value.append('"'); break;
                case '\\': value.append('\\'); break;
                case '/': value.append('/'); break;
                case 'b': value.append('\b'); break;
                case 'f': value.append('\f'); break;
                case 'n': value.append('\n'); break;
                case 'r': value.append('\r'); break;
                case 't': value.append('\t'); break;
                case 'u': value.append(readUnicodeEscape()); break;
                default: throw new IllegalArgumentException("Invalid JSON");
            }
        }
        throw new IllegalArgumentException("Invalid JSON");
    }

    private char readUnicodeEscape() {
        if (offset + 4 > source.length()) {
            throw new IllegalArgumentException("Invalid JSON");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(source.charAt(offset++), 16);
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid JSON");
            }
            value = (value << 4) | digit;
        }
        return (char) value;
    }

    private BigDecimal readNumber() {
        int start = offset;
        consume('-');
        if (consume('0')) {
            if (offset < source.length() && isDigit(source.charAt(offset))) {
                throw new IllegalArgumentException("Invalid JSON");
            }
        } else {
            requireDigit();
            while (offset < source.length() && isDigit(source.charAt(offset))) {
                offset++;
            }
        }
        if (consume('.')) {
            requireDigit();
            while (offset < source.length() && isDigit(source.charAt(offset))) {
                offset++;
            }
        }
        if (consume('e') || consume('E')) {
            if (!consume('+')) {
                consume('-');
            }
            requireDigit();
            while (offset < source.length() && isDigit(source.charAt(offset))) {
                offset++;
            }
        }
        try {
            return new BigDecimal(source.substring(start, offset));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid JSON");
        }
    }

    private void readLiteral(String literal) {
        if (!source.regionMatches(offset, literal, 0, literal.length())) {
            throw new IllegalArgumentException("Invalid JSON");
        }
        offset += literal.length();
    }

    private void requireDigit() {
        if (offset >= source.length() || source.charAt(offset) < '0' || source.charAt(offset) > '9') {
            throw new IllegalArgumentException("Invalid JSON");
        }
        offset++;
    }

    private boolean consume(char expected) {
        if (offset < source.length() && source.charAt(offset) == expected) {
            offset++;
            return true;
        }
        return false;
    }

    private void require(char expected) {
        if (!consume(expected)) {
            throw new IllegalArgumentException("Invalid JSON");
        }
    }

    private void skipWhitespace() {
        while (offset < source.length()) {
            char next = source.charAt(offset);
            if (next == ' ' || next == '\t' || next == '\r' || next == '\n') {
                offset++;
            } else {
                return;
            }
        }
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    static String quote(String value) {
        StringBuilder json = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"': json.append("\\\""); break;
                case '\\': json.append("\\\\"); break;
                case '\b': json.append("\\b"); break;
                case '\f': json.append("\\f"); break;
                case '\n': json.append("\\n"); break;
                case '\r': json.append("\\r"); break;
                case '\t': json.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        String hex = Integer.toHexString(character);
                        json.append("\\u");
                        for (int pad = hex.length(); pad < 4; pad++) {
                            json.append('0');
                        }
                        json.append(hex);
                    } else {
                        json.append(character);
                    }
            }
        }
        return json.append('"').toString();
    }
}
