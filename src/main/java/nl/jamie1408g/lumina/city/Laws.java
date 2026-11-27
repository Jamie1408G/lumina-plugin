package nl.jamie1408g.lumina.city;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lumina's law book, as written in laws.yml. */
public record Laws(String bookTitle, String title, String subtitle, String motto, List<String> intro,
                   List<Part> parts, List<String> closing) {

    /** @param penalty e.g. "7 days" or "2 hours + build removed" */
    public record Offence(String name, String penalty) {
    }

    /** @param kind short word shown in searches, e.g. "jail" or "CWO" */
    public record Part(String name, String note, String kind, List<String> text, List<Offence> offences) {
    }

    public static final Laws NONE = new Laws("Lumina Laws", "", "", "", List.of(), List.of(), List.of());

    public boolean isEmpty() {
        return parts.isEmpty() && intro.isEmpty() && closing.isEmpty();
    }

    /** Reads the laws: section of laws.yml. */
    public static Laws load(ConfigurationSection l, List<String> warnings) {
        if (l == null) {
            return NONE;
        }
        List<Part> parts = new ArrayList<>();
        ConfigurationSection ps = l.getConfigurationSection("parts");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                ConfigurationSection p = ps.getConfigurationSection(key);
                if (p == null) {
                    warnings.add("laws.parts." + key + " is not a section (expected name, offences...).");
                    continue;
                }
                List<Offence> offences = new ArrayList<>();
                for (String line : p.getStringList("offences")) {
                    offences.add(parseOffence(line));
                }
                parts.add(new Part(p.getString("name", key), p.getString("note", ""), p.getString("kind", key),
                        p.getStringList("text"), offences));
            }
        }
        String bookTitle = l.getString("book-title", "Lumina Laws");
        if (bookTitle.length() > 32) {
            warnings.add("laws.book-title is longer than Minecraft's 32-letter limit; it was shortened.");
            bookTitle = bookTitle.substring(0, 32);
        }
        return new Laws(bookTitle, l.getString("title", ""), l.getString("subtitle", ""), l.getString("motto", ""),
                l.getStringList("intro"), parts, l.getStringList("closing"));
    }

    /** "Killing a player | 7 days" -> Offence. Without "|" the whole line is the name. */
    static Offence parseOffence(String line) {
        int bar = line.indexOf('|');
        if (bar < 0) {
            return new Offence(line.trim(), "");
        }
        return new Offence(line.substring(0, bar).trim(), line.substring(bar + 1).trim());
    }

    /** Offences whose name contains the search text: {name, penalty, kind}. */
    public List<String[]> search(String query) {
        String k = normalize(query);
        List<String[]> out = new ArrayList<>();
        if (k.isEmpty()) {
            return out;
        }
        for (Part p : parts) {
            for (Offence o : p.offences()) {
                if (normalize(o.name()).contains(k)) {
                    out.add(new String[] {o.name(), o.penalty(), p.kind()});
                }
            }
        }
        return out;
    }

    /** Lowercase letters and digits only, so "dirt-pillar" finds "dirt pillars". */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                b.appendCodePoint(Character.toLowerCase(cp));
            }
            i += Character.charCount(cp);
        }
        return b.toString().toLowerCase(Locale.ROOT);
    }
}
