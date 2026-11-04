package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny text-level view of a JavaScript asset for JVM source tests (no JS engine on the JVM
 * test classpath). It understands just enough lexing (strings, template literals, comments,
 * regex literals) to match braces, find function bodies and follow calls between named
 * functions. Gradle runs :app tests with cwd = app/.
 */
final class JsSource {

    static final Path INJECT_JS = Paths.get("src/main/assets/inject.js");

    /** Source with every comment blanked to spaces (offsets preserved). */
    final String code;

    private JsSource(String raw) {
        this.code = blankComments(raw);
    }

    static JsSource injectJs() throws IOException {
        assertTrue("inject.js missing at " + INJECT_JS.toAbsolutePath(), Files.exists(INJECT_JS));
        return new JsSource(new String(Files.readAllBytes(INJECT_JS), StandardCharsets.UTF_8));
    }

    static JsSource of(String raw) {
        return new JsSource(raw);
    }

    // ------------------------------------------------------------------ lexing

    /** Index just past the literal/comment starting at i, or -1 when i starts none. */
    private static int skipNonCode(String s, int i) {
        char c = s.charAt(i);
        if (c == '\'' || c == '"' || c == '`') {
            int j = i + 1;
            while (j < s.length()) {
                char d = s.charAt(j);
                if (d == '\\') { j += 2; continue; }
                if (d == c) return j + 1;
                if (d == '\n' && c != '`') return j;
                j++;
            }
            return s.length();
        }
        if (c == '/' && i + 1 < s.length()) {
            char n = s.charAt(i + 1);
            if (n == '/') {
                int j = s.indexOf('\n', i);
                return j < 0 ? s.length() : j;
            }
            if (n == '*') {
                int j = s.indexOf("*/", i + 2);
                return j < 0 ? s.length() : j + 2;
            }
            if (regexAllowedBefore(s, i)) {
                int j = i + 1;
                boolean inClass = false;
                while (j < s.length()) {
                    char d = s.charAt(j);
                    if (d == '\\') { j += 2; continue; }
                    if (d == '\n') return j;
                    if (d == '[') inClass = true;
                    else if (d == ']') inClass = false;
                    else if (d == '/' && !inClass) return j + 1;
                    j++;
                }
                return s.length();
            }
        }
        return -1;
    }

    private static boolean regexAllowedBefore(String s, int i) {
        int k = i - 1;
        while (k >= 0 && Character.isWhitespace(s.charAt(k))) k--;
        if (k < 0) return true;
        char p = s.charAt(k);
        if ("(,=:[!&|?{};+-*%<>~^".indexOf(p) >= 0) return true;
        // keywords such as `return /x/`
        int e = k + 1;
        while (k >= 0 && Character.isLetter(s.charAt(k))) k--;
        String word = s.substring(k + 1, e);
        return word.equals("return") || word.equals("typeof") || word.equals("case");
    }

    private static String blankComments(String s) {
        StringBuilder out = new StringBuilder(s);
        int i = 0;
        while (i < s.length()) {
            int end = skipNonCode(s, i);
            if (end < 0) { i++; continue; }
            if (s.charAt(i) == '/' && (s.charAt(i + 1) == '/' || s.charAt(i + 1) == '*')) {
                for (int k = i; k < end; k++) {
                    if (out.charAt(k) != '\n') out.setCharAt(k, ' ');
                }
            }
            i = end;
        }
        return out.toString();
    }

    /** Index of the bracket matching the opener at {@code open}, or -1. */
    int matching(int open) {
        char o = code.charAt(open);
        char c = o == '{' ? '}' : o == '(' ? ')' : ']';
        int depth = 0;
        int i = open;
        while (i < code.length()) {
            int end = skipNonCode(code, i);
            if (end >= 0) { i = end; continue; }
            char ch = code.charAt(i);
            if (ch == o) depth++;
            else if (ch == c) {
                depth--;
                if (depth == 0) return i;
            }
            i++;
        }
        return -1;
    }

    // ------------------------------------------------------------- functions

    /** A function found in the source: name (may be null), params, body range [start, end]. */
    static final class Fn {
        final String name;
        final List<String> params;
        final int start; // index of the body's '{'
        final int end;   // index of the body's '}'
        final String body;

        Fn(String name, List<String> params, int start, int end, String body) {
            this.name = name;
            this.params = params;
            this.start = start;
            this.end = end;
            this.body = body;
        }
    }

    private static final Pattern FUNCTION_KW = Pattern.compile("\\bfunction\\b\\s*\\*?\\s*([A-Za-z_$][\\w$]*)?\\s*\\(");
    private static final Pattern ARROW = Pattern.compile("=>\\s*\\{");

    /** Every function/arrow body in the file, named where the declaration or assignment names it. */
    List<Fn> allFunctions() {
        List<Fn> out = new ArrayList<>();
        Matcher m = FUNCTION_KW.matcher(code);
        while (m.find()) {
            if (insideNonCode(m.start())) continue;
            int parenOpen = m.end() - 1;
            int parenClose = matching(parenOpen);
            if (parenClose < 0) continue;
            int brace = code.indexOf('{', parenClose);
            if (brace < 0) continue;
            int close = matching(brace);
            if (close < 0) continue;
            String name = m.group(1);
            if (name == null) name = assignedName(m.start());
            out.add(new Fn(name, params(code.substring(parenOpen + 1, parenClose)), brace, close,
                    code.substring(brace, close + 1)));
        }
        Matcher a = ARROW.matcher(code);
        while (a.find()) {
            if (insideNonCode(a.start())) continue;
            int brace = a.end() - 1;
            int close = matching(brace);
            if (close < 0) continue;
            // params: "(a, b) =>" or "a =>"
            int k = a.start() - 1;
            while (k >= 0 && Character.isWhitespace(code.charAt(k))) k--;
            List<String> ps = new ArrayList<>();
            int headStart = k;
            if (k >= 0 && code.charAt(k) == ')') {
                int depth = 0;
                int j = k;
                for (; j >= 0; j--) {
                    char ch = code.charAt(j);
                    if (ch == ')') depth++;
                    else if (ch == '(' && --depth == 0) break;
                }
                if (j >= 0) {
                    ps = params(code.substring(j + 1, k));
                    headStart = j;
                }
            } else {
                int e = k + 1;
                while (k >= 0 && (Character.isLetterOrDigit(code.charAt(k)) || code.charAt(k) == '_' || code.charAt(k) == '$')) k--;
                if (e > k + 1) ps.add(code.substring(k + 1, e));
                headStart = k + 1;
            }
            out.add(new Fn(assignedName(headStart), ps, brace, close, code.substring(brace, close + 1)));
        }
        return out;
    }

    /** "X = function" / "X: function" / "var X = (...) =>" - returns the last segment of X. */
    private String assignedName(int exprStart) {
        int k = exprStart - 1;
        while (k >= 0 && Character.isWhitespace(code.charAt(k))) k--;
        if (k < 0) return null;
        char op = code.charAt(k);
        if (op != '=' && op != ':') return null;
        if (op == '=' && k > 0 && "=!<>".indexOf(code.charAt(k - 1)) >= 0) return null;
        k--;
        while (k >= 0 && Character.isWhitespace(code.charAt(k))) k--;
        int e = k + 1;
        while (k >= 0 && (Character.isLetterOrDigit(code.charAt(k)) || code.charAt(k) == '_' || code.charAt(k) == '$')) k--;
        if (e <= k + 1) return null;
        String n = code.substring(k + 1, e);
        if (n.startsWith("'") || n.startsWith("\"")) return null;
        return n;
    }

    private boolean insideNonCode(int pos) {
        int i = 0;
        while (i < pos) {
            int end = skipNonCode(code, i);
            if (end < 0) { i++; continue; }
            if (end > pos) return true;
            i = end;
        }
        return false;
    }

    private static List<String> params(String raw) {
        List<String> out = new ArrayList<>();
        for (String p : raw.split(",")) {
            String t = p.trim();
            int eq = t.indexOf('=');
            if (eq >= 0) t = t.substring(0, eq).trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Functions bound to {@code name} (declaration, assignment or window.name = ...). */
    List<Fn> named(String name) {
        List<Fn> out = new ArrayList<>();
        for (Fn f : allFunctions()) {
            if (name.equals(f.name)) out.add(f);
        }
        if (out.isEmpty()) {
            // window.name = otherIdentifier;
            Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*([A-Za-z_$][\\w$]*)\\s*[;\\n]").matcher(code);
            while (m.find()) {
                String alias = m.group(1);
                if (!alias.equals("function") && !alias.equals(name)) out.addAll(named(alias));
            }
        }
        return out;
    }

    /** The first function bound to {@code name}, or null. */
    Fn first(String name) {
        List<Fn> l = named(name);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Innermost function body containing {@code pos}. */
    Fn enclosing(int pos) {
        Fn best = null;
        for (Fn f : allFunctions()) {
            if (f.start < pos && pos < f.end && (best == null || f.end - f.start < best.end - best.start)) {
                best = f;
            }
        }
        return best;
    }

    /**
     * Text of the given bodies plus, transitively, the bodies of every named function they
     * call, skipping functions declared inside {@code excludedRoots} (e.g. voice2sms) and any
     * function whose name matches {@code skipName}.
     */
    String closure(List<Fn> roots, Set<String> excludedRoots, Pattern skipName) {
        List<int[]> excluded = new ArrayList<>();
        for (String ex : excludedRoots) {
            for (Fn f : named(ex)) excluded.add(new int[]{f.start, f.end});
        }
        List<Fn> all = allFunctions();
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        List<Fn> queue = new ArrayList<>(roots);
        Set<Integer> added = new LinkedHashSet<>();
        Pattern call = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*\\(");
        while (!queue.isEmpty()) {
            Fn f = queue.remove(0);
            if (!added.add(f.start)) continue;
            if (f.name != null && skipName != null && skipName.matcher(f.name).find()) continue;
            String body = f.body;
            if (skipName != null) body = stripNamed(body, f.start, all, skipName);
            sb.append(body).append('\n');
            Matcher m = call.matcher(body);
            while (m.find()) {
                String n = m.group(1);
                if (!seen.add(n)) continue;
                for (Fn g : all) {
                    if (!n.equals(g.name) || isWithin(g, excluded)) continue;
                    queue.add(g);
                }
            }
        }
        return sb.toString();
    }

    private static boolean isWithin(Fn g, List<int[]> ranges) {
        for (int[] r : ranges) {
            if (g.start > r[0] && g.end < r[1]) return true;
        }
        return false;
    }

    /** Body text with nested functions whose name matches {@code skip} blanked out. */
    private static String stripNamed(String body, int bodyStart, List<Fn> all, Pattern skip) {
        StringBuilder sb = new StringBuilder(body);
        for (Fn g : all) {
            if (g.name == null || !skip.matcher(g.name).find()) continue;
            int s = g.start - bodyStart;
            int e = g.end - bodyStart;
            if (s <= 0 || e >= body.length()) continue;
            for (int k = s; k <= e; k++) {
                if (sb.charAt(k) != '\n') sb.setCharAt(k, ' ');
            }
        }
        return sb.toString();
    }

    /** {@code code} with every function whose name matches {@code skip} blanked out. */
    String withoutNamed(Pattern skip) {
        StringBuilder sb = new StringBuilder(code);
        for (Fn g : allFunctions()) {
            if (g.name == null || !skip.matcher(g.name).find()) continue;
            // also blank the "function name(...)" head so the helper vanishes entirely
            int headStart = code.lastIndexOf("function", g.start);
            if (headStart < 0) headStart = g.start;
            for (int k = headStart; k <= g.end; k++) {
                if (sb.charAt(k) != '\n') sb.setCharAt(k, ' ');
            }
        }
        return sb.toString();
    }
}
