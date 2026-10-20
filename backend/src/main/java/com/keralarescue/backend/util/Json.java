package com.keralarescue.backend.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny dependency-free JSON reader/writer used by the AI, JWT and notification clients.
 * Parsed values are Map&lt;String,Object&gt;, List&lt;Object&gt;, String, Double, Boolean or null.
 */
public final class Json {
    private Json() { }

    public static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Serialises Map, List, String, Number, Boolean and null. */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof String s) sb.append(quote(s));
        else if (v instanceof Boolean || v instanceof Number) sb.append(v);
        else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(String.valueOf(e.getKey()))).append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else sb.append(quote(String.valueOf(v)));
    }

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("empty json");
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) throw new IllegalArgumentException("trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("not a JSON object");
        return (Map<String, Object>) v;
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static final class Parser {
        final String s;
        int i;
        Parser(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value(int depth) {
            if (depth > 64) throw new IllegalArgumentException("too deep");
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            if (c == '{') return obj(depth);
            if (c == '[') return arr(depth);
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> obj(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw new IllegalArgumentException("expected key");
                String k = string(); ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new IllegalArgumentException("expected colon");
                i++; ws();
                m.put(k, value(depth + 1)); ws();
                if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected comma");
            }
        }

        List<Object> arr(int depth) {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1)); ws();
                if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected comma");
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw new IllegalArgumentException("bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw new IllegalArgumentException("bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape");
                }
            }
        }

        Double number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalArgumentException("unexpected character");
            return Double.valueOf(s.substring(start, i));
        }
    }
}
