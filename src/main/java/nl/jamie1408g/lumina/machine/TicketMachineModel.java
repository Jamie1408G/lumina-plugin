package nl.jamie1408g.lumina.machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Ticket machines, modelled on Singapore MRT and LRT ticketing machines. Two types:
 *
 *  - MRT: a white kiosk 0.9 m wide and 2 m tall with a Lumina-blue header, a touch screen leaning
 *    back slightly, a card reader with a yellow target, a note slot, a card tray at knee height and
 *    blue light strips down the front edges.
 *  - LRT: the compact version for LRT platforms, 0.66 m wide and 1.75 m tall with a green header;
 *    card only, so no note slot.
 *
 * The reader light and the status light turn green when something is paid and red when it's refused.
 * Coordinates are the machine's own: the middle of the block's floor is (0, 0, 0), x runs across,
 * y up, and the front faces -z (towards the admin who placed it). Parts are boxes: corner offset,
 * size, then an optional tilt (degrees, around the x axis through the corner). Built from display
 * entities, so no resource pack is needed.
 */
public final class TicketMachineModel {

    /** Bump when the model changes; /lumina machine rebuild updates old machines. */
    public static final int VERSION = 2;

    public enum Kind { STATIC, LIGHT }

    public record Part(String name, Kind kind, String material, float tx, float ty, float tz,
                       float sx, float sy, float sz, float tiltDeg, boolean glow) {}

    public enum Type {
        MRT(0.45f, 1.80f, 0.50f, "LIGHT_BLUE_CONCRETE", true, 0.33f),
        LRT(0.33f, 1.55f, 0.42f, "LIME_CONCRETE", false, 0.27f);

        final float halfWidth;
        final float top;
        final float depth;
        final String colour;
        final boolean noteSlot;
        final float labelScale;
        private List<Part> parts;

        Type(float halfWidth, float top, float depth, String colour, boolean noteSlot, float labelScale) {
            this.halfWidth = halfWidth;
            this.top = top;
            this.depth = depth;
            this.colour = colour;
            this.noteSlot = noteSlot;
            this.labelScale = labelScale;
        }

        public List<Part> parts() {
            if (parts == null) {
                parts = build(this);
            }
            return parts;
        }

        /** Height of the click box. */
        public float height() {
            return top + 0.2f;
        }

        public float labelY() {
            return top + 0.03f;
        }

        public float labelScale() {
            return labelScale;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** "mrt" or "lrt", else null. */
        public static Type parse(String text) {
            if (text == null) {
                return null;
            }
            for (Type t : values()) {
                if (t.name().equalsIgnoreCase(text.trim())) {
                    return t;
                }
            }
            return null;
        }
    }

    public static final String OK = "LIME_CONCRETE";
    public static final String NO = "RED_CONCRETE";

    static final float FRONT = -0.12f;  // front face of the body

    /** Where the name on the header goes (machine coordinates). */
    public static final float LABEL_Z = -0.152f;

    private TicketMachineModel() {}

    private static List<Part> build(Type t) {
        float hw = t.halfWidth, top = t.top, depth = t.depth;
        float sw = 2 * hw - 0.3f;            // screen width
        float sh = t == Type.MRT ? 0.44f : 0.38f;
        float sy = top - 0.62f;               // bottom of the screen
        float tan = (float) Math.tan(Math.toRadians(10));
        List<Part> p = new ArrayList<>();
        p.add(new Part("plinth", Kind.STATIC, "GRAY_CONCRETE", -hw, 0f, FRONT - 0.01f, 2 * hw, 0.08f, depth + 0.02f, 0, false));
        p.add(new Part("body", Kind.STATIC, "WHITE_CONCRETE", -hw, 0.08f, FRONT, 2 * hw, top - 0.08f, depth, 0, false));
        p.add(new Part("header", Kind.STATIC, t.colour, -hw - 0.01f, top, FRONT - 0.02f, 2 * hw + 0.02f, 0.2f, depth + 0.04f, 0, true));
        p.add(new Part("header sign", Kind.STATIC, "WHITE_CONCRETE", -hw + 0.07f, top + 0.03f, FRONT - 0.026f, 2 * hw - 0.14f, 0.14f, 0.004f, 0, true));
        // Light strips down both front edges, in the header's colour.
        p.add(new Part("left strip", Kind.LIGHT, t.colour, -hw, 0.1f, FRONT - 0.005f, 0.03f, top - 0.12f, 0.005f, 0, true));
        p.add(new Part("right strip", Kind.LIGHT, t.colour, hw - 0.03f, 0.1f, FRONT - 0.005f, 0.03f, top - 0.12f, 0.005f, 0, true));
        // Touch screen, leaning back 10 degrees, with glowing glass just in front of its housing.
        p.add(new Part("screen housing", Kind.STATIC, "BLACK_CONCRETE", -sw / 2, sy, FRONT - 0.04f, sw, sh, 0.04f, 10f, false));
        p.add(new Part("screen", Kind.STATIC, "LIGHT_BLUE_CONCRETE", -sw / 2 + 0.03f, sy + 0.03f, FRONT - 0.046f, sw - 0.06f, sh - 0.06f, 0.004f, 10f, true));
        float barRise = sh - 0.12f;
        p.add(new Part("screen bar", Kind.STATIC, "WHITE_CONCRETE", -sw / 2 + 0.03f, sy + 0.03f + barRise,
                FRONT - 0.0475f + barRise * tan, sw - 0.06f, 0.05f, 0.004f, 10f, true));
        // Card reader under the screen (right), and on the MRT machine a note slot (left).
        p.add(new Part("card reader", Kind.STATIC, "BLACK_CONCRETE", sw / 2 - 0.2f, sy - 0.22f, FRONT - 0.03f, 0.26f, 0.16f, 0.03f, 0, false));
        p.add(new Part("reader light", Kind.LIGHT, "YELLOW_CONCRETE", sw / 2 - 0.17f, sy - 0.19f, FRONT - 0.035f, 0.2f, 0.1f, 0.005f, 0, true));
        if (t.noteSlot) {
            p.add(new Part("note slot", Kind.STATIC, "GRAY_CONCRETE", -sw / 2 - 0.06f, sy - 0.18f, FRONT - 0.02f, 0.3f, 0.08f, 0.02f, 0, false));
            p.add(new Part("note slot opening", Kind.STATIC, "BLACK_CONCRETE", -sw / 2 - 0.03f, sy - 0.15f, FRONT - 0.0205f, 0.24f, 0.02f, 0.001f, 0, false));
        }
        // Card tray at knee height.
        float tw = Math.min(0.4f, 2 * hw - 0.26f);
        p.add(new Part("tray opening", Kind.STATIC, "BLACK_CONCRETE", -tw / 2, 0.42f, FRONT - 0.002f, tw, 0.16f, 0.002f, 0, false));
        p.add(new Part("tray lip", Kind.STATIC, "LIGHT_GRAY_CONCRETE", -tw / 2 - 0.02f, 0.39f, FRONT - 0.07f, tw + 0.04f, 0.04f, 0.07f, 0, false));
        // Status light at the top right of the front.
        p.add(new Part("status light", Kind.LIGHT, "LIME_CONCRETE", hw - 0.13f, top - 0.1f, FRONT - 0.005f, 0.06f, 0.05f, 0.005f, 0, true));
        return Collections.unmodifiableList(p);
    }

    /** Snap a yaw to the nearest of the four directions (0 south, 90 west, 180 north, 270 east). */
    public static float snapYaw(float yaw) {
        int q = Math.floorMod(Math.round(yaw / 90f), 4);
        return q * 90f;
    }

    /** One block forward from (x, z) when facing this snapped yaw: {dx, dz}. */
    public static int[] forward(float snappedYaw) {
        return switch (Math.round(snappedYaw) % 360) {
            case 90 -> new int[] {-1, 0};
            case 180 -> new int[] {0, -1};
            case 270 -> new int[] {1, 0};
            default -> new int[] {0, 1};
        };
    }

    /** Machine coordinates (x, z) to a world offset {dx, dz}, turned the way the machine faces. */
    public static double[] toWorld(double lx, double lz, float snappedYaw) {
        double a = Math.toRadians(snappedYaw);
        double c = Math.round(Math.cos(a)), s = Math.round(Math.sin(a));
        return new double[] {lx * c - lz * s, lx * s + lz * c};
    }
}
