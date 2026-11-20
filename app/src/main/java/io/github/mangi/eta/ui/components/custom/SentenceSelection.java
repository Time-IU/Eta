package io.github.mangi.eta.ui.components.custom;

/** UTF-16 ranges, like TextLayoutResult. Scanned only on an explicit double tap. */
public final class SentenceSelection {
    private SentenceSelection() {}
    public static int[] range(String text, int offset) {
        if (text.isEmpty()) return new int[]{0, 0};
        int hit = Math.max(0, Math.min(offset, text.length() - 1));
        if (Character.isLowSurrogate(text.charAt(hit)) && hit > 0) hit--;
        int start = 0;
        for (int i = 0; i < text.length();) {
            int end = i + 1;
            char c = text.charAt(i);
            boolean newline = c == '\n' || c == '\r';
            if (newline || terminal(text, i)) {
                if (!newline) {
                    while (end < text.length() && (terminal(text, end) || closer(text.charAt(end)))) end++;
                } else if (c == '\r' && end < text.length() && text.charAt(end) == '\n') end++;
                int a = start, b = newline ? i : end;
                while (a < b && Character.isWhitespace(text.charAt(a))) a++;
                while (b > a && Character.isWhitespace(text.charAt(b - 1))) b--;
                // Whitespace after a sentence belongs to the following sentence, never the whole paragraph.
                if (hit < end && b > a) return new int[]{a, b};
                start = end;
            }
            i = end;
        }
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        return new int[]{start, end};
    }
    private static boolean closer(char c) { return "\"'”’」』）)]】》〉»".indexOf(c) >= 0; }
    private static boolean terminal(String s, int i) {
        char c = s.charAt(i);
        if ("。！？!?；;…".indexOf(c) >= 0) return true;
        if (c != '.') return false;
        // Do not split decimals, URLs, domains or initials inside a token.
        if (i > 0 && i + 1 < s.length() && Character.isLetterOrDigit(s.charAt(i - 1)) && Character.isLetterOrDigit(s.charAt(i + 1))) return false;
        int from = i;
        while (from > 0 && (Character.isLetter(s.charAt(from - 1)) || s.charAt(from - 1) == '.')) from--;
        String word = s.substring(from, i).toLowerCase(java.util.Locale.ROOT);
        if (java.util.Arrays.asList("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "e.g", "i.e").contains(word)) return false;
        return true;
    }
}
