package net.kdt.pojavlaunch.performance;

import java.util.ArrayList;

/** Ordered argv tokenizer, not a shell: no expansion, prefix sorting or '=' validation.
 * Whitespace separates tokens outside quotes. Backslash escapes whitespace, quotes and backslash;
 * other backslashes (including Windows paths) are literal. Malformed quotes fail explicitly. */
public final class RuntimeArgumentTokenizer {
    private RuntimeArgumentTokenizer() {}

    public static ArrayList<String> parse(String input) {
        ArrayList<String> out = new ArrayList<>();
        if (input == null || input.isEmpty()) return out;
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && quote != '\'' && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                if (Character.isWhitespace(next) || next == '\\' || next == '"' || next == '\'') {
                    token.append(next);
                    started = true;
                    i++;
                    continue;
                }
            }
            if (quote != 0) {
                if (c == quote) quote = 0;
                else token.append(c);
                started = true;
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) { out.add(token.toString()); token.setLength(0); started = false; }
            } else {
                token.append(c);
                started = true;
            }
        }
        if (quote != 0) throw new IllegalArgumentException("Unclosed quote in Java arguments");
        if (started) out.add(token.toString());
        return out;
    }
}
