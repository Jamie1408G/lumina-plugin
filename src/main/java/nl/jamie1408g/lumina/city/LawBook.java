package nl.jamie1408g.lumina.city;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays the laws out as written-book pages. Minecraft cuts off text that runs past the bottom of a
 * page, so lines are wrapped here using the game font's letter widths, and blocks (an offence, a
 * heading with what follows it) are kept together on one page.
 */
public final class LawBook {
    private static final char S = '\u00a7';
    /** Book text area is 114 px wide; a little margin so the game never wraps a line itself. */
    static final int WIDTH = 110;
    static final int LINES = 14;

    // Dark colours: book pages are cream, so light chat colours would be unreadable.
    private static final String HEAD = S + "3" + S + "l";   // dark aqua bold
    private static final String BODY = S + "0";             // black
    private static final String NOTE = S + "8" + S + "o";   // dark grey italic
    private static final String DIM = S + "8";
    private static final String STRONG = S + "4" + S + "l"; // dark red bold
    private static final String PENALTY = S + "4";           // dark red

    private record Block(List<String> lines, boolean keepWithNext) {}

    private LawBook() {}

    public static List<String> pages(Laws laws) {
        List<String> cover = paginate(coverBlocks(laws));
        List<List<String>> partPages = new ArrayList<>();
        for (Laws.Part p : laws.parts()) partPages.add(paginate(partBlocks(p)));
        List<String> closing = laws.closing().isEmpty() ? List.of() : paginate(closingBlocks(laws));

        List<String> out = new ArrayList<>(cover);
        if (!laws.parts().isEmpty()) {
            // Contents page: the parts start right after it.
            int page = cover.size() + 2;
            List<Block> contents = new ArrayList<>();
            contents.add(new Block(wrap(HEAD + "Contents"), true));
            contents.add(gap());
            for (int i = 0; i < laws.parts().size(); i++) {
                contents.add(new Block(wrap(DIM + "p." + page + " " + BODY + laws.parts().get(i).name()), false));
                page += partPages.get(i).size();
            }
            if (!closing.isEmpty()) contents.add(new Block(wrap(DIM + "p." + page + " " + BODY + "Appeals"), false));
            contents.add(gap());
            contents.add(new Block(wrap(NOTE + "Look up one offence in chat with /lulaws <word>, e.g. /lulaws griefing"), false));
            out.addAll(paginate(contents));
        }
        partPages.forEach(out::addAll);
        out.addAll(closing);
        return out;
    }

    private static List<Block> coverBlocks(Laws laws) {
        List<Block> b = new ArrayList<>();
        if (!laws.title().isBlank()) b.add(new Block(wrap(HEAD + laws.title()), true));
        if (!laws.subtitle().isBlank()) b.add(new Block(wrap(BODY + laws.subtitle()), true));
        if (!laws.motto().isBlank()) b.add(new Block(wrap(NOTE + laws.motto()), false));
        b.add(gap());
        for (String para : laws.intro()) { b.add(new Block(wrap(BODY + para), false)); b.add(gap()); }
        return b;
    }

    private static List<Block> partBlocks(Laws.Part p) {
        List<Block> b = new ArrayList<>();
        b.add(new Block(wrap(HEAD + p.name()), true));
        if (p.note() != null && !p.note().isBlank()) b.add(new Block(wrap(NOTE + p.note()), true));
        b.add(gap());
        for (String para : p.text()) { b.add(new Block(wrap(BODY + para), false)); b.add(gap()); }
        for (Laws.Offence o : p.offences()) b.add(new Block(offence(o), false));
        return b;
    }

    /** "Griefing — 4 days" on one line when it fits; otherwise the penalty goes on its own indented line. */
    private static List<String> offence(Laws.Offence o) {
        if (o.penalty().isEmpty()) return wrap(BODY + o.name());
        List<String> one = wrap(BODY + o.name() + DIM + " \u2014 " + PENALTY + o.penalty());
        if (one.size() == 1) return one;
        List<String> lines = new ArrayList<>(wrap(BODY + o.name()));
        for (String l : wrap(PENALTY + o.penalty(), WIDTH - 2 * charWidth(' '))) lines.add("  " + l);
        return lines;
    }

    private static List<Block> closingBlocks(Laws laws) {
        List<Block> b = new ArrayList<>();
        b.add(new Block(wrap(HEAD + "Appeals"), true));
        b.add(gap());
        for (String para : laws.closing()) { b.add(new Block(wrap(STRONG + para), false)); b.add(gap()); }
        return b;
    }

    private static Block gap() { return new Block(List.of(""), false); }

    /** Fill pages with blocks, never splitting a block (unless it's longer than a page). */
    static List<String> paginate(List<Block> blocks) {
        List<String> pages = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block blk = blocks.get(i);
            boolean blank = blk.lines().size() == 1 && blk.lines().get(0).isEmpty();
            if (blank) {
                if (!cur.isEmpty() && cur.size() < LINES) cur.add("");
                continue;
            }
            int need = blk.lines().size();
            // A heading drags the next real block along with it.
            for (int j = i; j < blocks.size() && blocks.get(j).keepWithNext() && j + 1 < blocks.size(); j++) {
                Block next = blocks.get(j + 1);
                if (!(next.lines().size() == 1 && next.lines().get(0).isEmpty())) need += next.lines().size();
            }
            if (!cur.isEmpty() && cur.size() + Math.min(need, LINES) > LINES) {
                pages.add(page(cur));
                cur = new ArrayList<>();
            }
            for (String line : blk.lines()) {
                if (cur.size() == LINES) { pages.add(page(cur)); cur = new ArrayList<>(); }
                cur.add(line);
            }
        }
        if (!cur.isEmpty()) pages.add(page(cur));
        if (pages.isEmpty()) pages.add("");
        return pages;
    }

    private static String page(List<String> lines) {
        List<String> l = new ArrayList<>(lines);
        while (!l.isEmpty() && l.get(l.size() - 1).isEmpty()) l.remove(l.size() - 1);
        return String.join("\n", l);
    }

    /**
     * Wrap one paragraph (with colour codes) into lines no wider than maxWidth (WIDTH by default).
     * Each continuation line starts with the colour and style that were active, so nothing is lost.
     */
    static List<String> wrap(String paragraph) {
        return wrap(paragraph, WIDTH);
    }

    static List<String> wrap(String paragraph, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        String style = "";
        boolean empty = true;
        for (String word : paragraph.split(" ")) {
            if (word.isEmpty()) continue;
            String before = style;
            int w = 0;
            boolean bold = style.contains(S + "l");
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                if (c == S && i + 1 < word.length()) {
                    char code = Character.toLowerCase(word.charAt(++i));
                    if ("0123456789abcdef".indexOf(code) >= 0) style = S + String.valueOf(code);
                    else if (code == 'r') style = "";
                    else style += S + String.valueOf(code);
                    bold = style.contains(S + "l");
                    continue;
                }
                w += charWidth(c) + (bold ? 1 : 0);
            }
            int space = charWidth(' ') + (before.contains(S + "l") ? 1 : 0);
            if (empty) {
                line.append(word);
                lineWidth = w;
                empty = false;
            } else if (lineWidth + space + w <= maxWidth) {
                line.append(' ').append(word);
                lineWidth += space + w;
            } else {
                lines.add(line.toString());
                line = new StringBuilder(before).append(word);
                lineWidth = w;
            }
        }
        if (!empty) lines.add(line.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    /** Width in pixels of a character in Minecraft's default font, including the 1 px gap. */
    static int charWidth(char c) {
        switch (c) {
            case 'i': case '!': case ',': case '.': case ':': case ';': case '|': case '\'': return 2;
            case 'l': case '`': return 3;
            case 'I': case 't': case ' ': case '[': case ']': case '(': case ')': case '{': case '}': case '"': case '*': return 4;
            case 'f': case 'k': case '<': case '>': return 5;
            case '@': case '~': return 7;
            default:
                // Letters, digits and most symbols are 6; anything outside ASCII may come from the wider fallback font.
                return c < 128 ? 6 : 9;
        }
    }

    /** Lines for reading the laws in chat (console, or when a book can't be shown). */
    public static List<String> chat(Laws laws) {
        List<String> out = new ArrayList<>();
        out.add(S + "b" + S + "l" + laws.title() + S + "r " + S + "7" + laws.subtitle());
        for (String para : laws.intro()) out.add(S + "7" + para);
        for (Laws.Part p : laws.parts()) {
            out.add(S + "3" + S + "l" + p.name() + (p.note() == null || p.note().isBlank() ? "" : S + "r " + S + "8" + p.note()));
            for (String para : p.text()) out.add(S + "7" + para);
            for (Laws.Offence o : p.offences()) out.add(S + "f " + o.name() + S + "8 — " + S + "c" + o.penalty());
        }
        for (String para : laws.closing()) out.add(S + "e" + para);
        return out;
    }
}
