package org.phoenix.core.provision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON reader for the generated lock file; avoids a runtime dependency. */
final class MiniJson {
    private final String text;
    private int at;

    private MiniJson(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        MiniJson parser = new MiniJson(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.at != text.length()) {
            throw parser.error("unexpected trailing content");
        }
        return value;
    }

    private Object readValue() {
        skipWhitespace();
        if (at >= text.length()) {
            throw error("expected a value");
        }
        return switch (text.charAt(at)) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        skipWhitespace();
        Map<String, Object> values = new LinkedHashMap<>();
        if (take('}')) {
            return values;
        }
        while (true) {
            skipWhitespace();
            if (at >= text.length() || text.charAt(at) != '"') {
                throw error("object key must be a string");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            Object value = readValue();
            if (values.containsKey(key)) {
                throw error("duplicate object key: " + key);
            }
            values.put(key, value);
            skipWhitespace();
            if (take('}')) {
                return values;
            }
            expect(',');
        }
    }

    private List<Object> readArray() {
        expect('[');
        skipWhitespace();
        List<Object> values = new ArrayList<>();
        if (take(']')) {
            return values;
        }
        while (true) {
            values.add(readValue());
            skipWhitespace();
            if (take(']')) {
                return values;
            }
            expect(',');
        }
    }

    private String readString() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (at < text.length()) {
            char value = text.charAt(at++);
            if (value == '"') {
                return result.toString();
            }
            if (value == '\\') {
                if (at >= text.length()) {
                    throw error("unterminated escape");
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(readUnicode());
                    default -> throw error("invalid string escape");
                }
            } else {
                if (value < 0x20) {
                    throw error("unescaped control character");
                }
                result.append(value);
            }
        }
        throw error("unterminated string");
    }

    private char readUnicode() {
        if (at + 4 > text.length()) {
            throw error("short unicode escape");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(at++), 16);
            if (digit < 0) {
                throw error("invalid unicode escape");
            }
            value = (value << 4) | digit;
        }
        return (char) value;
    }

    private Object readNumber() {
        int start = at;
        take('-');
        if (take('0')) {
            if (at < text.length() && Character.isDigit(text.charAt(at))) {
                throw error("leading zero in number");
            }
        } else {
            requireDigits();
        }
        boolean integral = true;
        if (take('.')) {
            integral = false;
            requireDigits();
        }
        if (take('e') || take('E')) {
            integral = false;
            if (!take('+')) {
                take('-');
            }
            requireDigits();
        }
        String number = text.substring(start, at);
        try {
            if (integral) {
                return Long.valueOf(number);
            }
            return Double.valueOf(number);
        } catch (NumberFormatException exception) {
            throw error("invalid number");
        }
    }

    private void requireDigits() {
        int start = at;
        while (at < text.length() && Character.isDigit(text.charAt(at))) {
            at++;
        }
        if (start == at) {
            throw error("expected digits");
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, at)) {
            throw error("invalid literal");
        }
        at += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (at < text.length()) {
            char value = text.charAt(at);
            if (value == ' ' || value == '\t' || value == '\n' || value == '\r') {
                at++;
            } else {
                return;
            }
        }
    }

    private boolean take(char expected) {
        if (at < text.length() && text.charAt(at) == expected) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!take(expected)) {
            throw error("expected '" + expected + "'");
        }
    }

    private IllegalArgumentException error(String reason) {
        return new IllegalArgumentException(reason + " at JSON offset " + at);
    }
}
