package nl.jamie1408g.lumina.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** The plain parts of updating: reading a GitHub release, comparing versions, checking a download. */
public final class UpdateCheck {

    /** @param checksumUrl the release's .sha256 file, or null if it has none */
    public record Release(String version, String jarUrl, String checksumUrl) {}

    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ASSET = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern HEX64 = Pattern.compile("\\b([0-9a-fA-F]{64})\\b");

    private UpdateCheck() {}

    /** Read GitHub's "latest release" JSON. Null if it has no tag or no .jar file attached. */
    public static Release parse(String json) {
        Matcher t = TAG.matcher(json);
        if (!t.find()) return null;
        String version = t.group(1).trim();
        if (version.startsWith("v") || version.startsWith("V")) version = version.substring(1);
        String jar = null, sum = null;
        Matcher a = ASSET.matcher(json);
        while (a.find()) {
            String url = a.group(1);
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jar.sha256") || lower.endsWith(".sha256")) { if (sum == null) sum = url; }
            else if (lower.endsWith(".jar")) { if (jar == null) jar = url; }
        }
        return jar == null ? null : new Release(version, jar, sum);
    }

    /**
     * Compare versions like 1.9.0 and 1.10.0 number by number. A version with a suffix
     * (1.10.0-beta) counts as just below the same version without one.
     */
    public static int compare(String a, String b) {
        String[] pa = core(a).split("\\."), pb = core(b).split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length ? num(pa[i]) : 0, y = i < pb.length ? num(pb[i]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        boolean sa = a.contains("-"), sb = b.contains("-");
        if (sa != sb) return sa ? -1 : 1;
        return 0;
    }

    private static String core(String v) {
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int dash = s.indexOf('-');
        return dash < 0 ? s : s.substring(0, dash);
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.replaceAll("[^0-9]", "").isEmpty() ? "0" : s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The first 64-hex-digit hash in a .sha256 file ("<hash>  Lumina-1.10.0.jar"), lowercased; null if none. */
    public static String expectedHash(String checksumFile) {
        Matcher m = HEX64.matcher(checksumFile);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** The plugin name in a jar's plugin.yml, or null if it isn't a plugin jar. */
    public static String pluginName(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry("plugin.yml");
            if (e == null) return null;
            String yml = new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
            for (String line : yml.split("\\R")) {
                if (line.startsWith("name:")) return line.substring(5).trim().replace("\"", "").replace("'", "");
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }
}
