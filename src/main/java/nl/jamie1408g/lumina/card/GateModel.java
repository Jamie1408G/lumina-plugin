package nl.jamie1408g.lumina.card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The modern faregate, modelled on real metro flap gates (Singapore MRT TITAN / Thales style):
 *
 *  - brushed-steel cabinets about 0.95 m tall and 1.4 m long; each gate has half a cabinet on
 *    each side, so a row of gates shares 0.3 m cabinets between lanes, like a real gate line
 *  - a 0.7 m lane with two central flaps (frosted panel, red leading edge) that retract into the
 *    cabinets when a card is accepted
 *  - a card reader at each end (bidirectional), on the cabinet to the walker's right, with a yellow
 *    target that turns green or red, and an angled fare screen beside it
 *  - an LED lane bar along the top inner edge of each cabinet and LED direction signs on the end faces
 *  - every light on the gate (readers, screens, lane bars, signs) turns green when a card is accepted
 *    and red when it's refused
 *
 * Coordinates are the gate's own: the middle of the lane's floor is (0, 0, 0), x runs across the lane,
 * y up, +z is one walking direction. Parts are boxes: corner offset, size, then an optional tilt
 * (degrees, around the x axis through the corner).
 */
public final class GateModel {

    /** Bump when the model changes; /lumina gate rebuild updates old gates. */
    public static final int VERSION = 2;

    public enum Kind { STATIC, LIGHT, FLAP, EDGE }

    public record Part(String name, Kind kind, String material, float tx, float ty, float tz,
                       float sx, float sy, float sz, float tiltDeg, boolean glow) {}

    /** A tap hitbox: centre of its base (gate coordinates) and size. */
    public record Hitbox(float x, float y, float z, float width, float height) {}

    public static final String OK = "LIME_CONCRETE";
    public static final String NO = "RED_CONCRETE";

    // Real-world sizes, in blocks (1 block = 1 m)
    static final float INNER = 0.35f;          // cabinet inner faces at x = ±0.35 -> 0.7 m lane
    static final float HALF_LEN = 0.70f;       // cabinets run from z = -0.7 to +0.7 (1.4 m)
    static final float BODY_H = 0.92f, TOP_H = 0.03f;
    static final float FLAP_Y = 0.40f, FLAP_H = 0.48f, FLAP_T = 0.03f;
    static final float TIP = 0.015f;           // closed flaps stop 1.5 cm short of the middle
    static final float EDGE_W = 0.02f;
    static final float GONE = 0.001f;

    public static final List<Part> PARTS;
    public static final List<Hitbox> HITBOXES = List.of(
            new Hitbox(0f, 0f, 0f, 1.0f, 1.1f),                // the whole lane
            new Hitbox(-0.425f, 0.88f, -0.55f, 0.32f, 0.22f),  // entry-end reader
            new Hitbox(0.425f, 0.88f, 0.55f, 0.32f, 0.22f));   // exit-end reader

    static {
        List<Part> p = new ArrayList<>();
        float top = BODY_H + TOP_H;
        for (int side = -1; side <= 1; side += 2) {
            float outer = side < 0 ? -0.5f : INNER;      // x where this half-cabinet starts
            String s = side < 0 ? "left" : "right";
            p.add(new Part(s + " cabinet", Kind.STATIC, "LIGHT_GRAY_CONCRETE", outer, 0f, -HALF_LEN, 0.15f, BODY_H, 2 * HALF_LEN, 0, false));
            p.add(new Part(s + " top", Kind.STATIC, "GRAY_CONCRETE", outer, BODY_H, -HALF_LEN, 0.15f, TOP_H, 2 * HALF_LEN, 0, false));
            float barX = side < 0 ? -INNER : INNER - 0.004f;
            p.add(new Part(s + " lane bar", Kind.LIGHT, "LIGHT_BLUE_CONCRETE", barX, 0.84f, -HALF_LEN + 0.02f, 0.004f, 0.035f, 2 * HALF_LEN - 0.04f, 0, true));
            float signX = side < 0 ? -0.47f : INNER + 0.03f;
            p.add(new Part(s + " sign (entry end)", Kind.LIGHT, "LIME_CONCRETE", signX, 0.62f, -HALF_LEN - 0.004f, 0.09f, 0.09f, 0.004f, 0, true));
            p.add(new Part(s + " sign (exit end)", Kind.LIGHT, "LIME_CONCRETE", signX, 0.62f, HALF_LEN, 0.09f, 0.09f, 0.004f, 0, true));
        }
        // Readers: entry end on the left cabinet (walker's right going +z), exit end on the right cabinet.
        p.add(new Part("entry reader", Kind.STATIC, "BLACK_CONCRETE", -0.49f, top, -0.66f, 0.13f, 0.012f, 0.18f, 0, false));
        p.add(new Part("entry reader light", Kind.LIGHT, "YELLOW_CONCRETE", -0.47f, top + 0.012f, -0.64f, 0.09f, 0.006f, 0.14f, 0, true));
        p.add(new Part("exit reader", Kind.STATIC, "BLACK_CONCRETE", INNER + 0.01f, top, HALF_LEN - 0.22f, 0.13f, 0.012f, 0.18f, 0, false));
        p.add(new Part("exit reader light", Kind.LIGHT, "YELLOW_CONCRETE", INNER + 0.03f, top + 0.012f, HALF_LEN - 0.20f, 0.09f, 0.006f, 0.14f, 0, true));
        // Angled fare screens, leaning back so they face the person tapping.
        p.add(screen("entry screen", -0.49f, top, -0.44f, -35f, false));
        p.add(screen("entry screen glass", -0.49f, top, -0.44f, -35f, true));
        float rise = (float) (0.10 * Math.sin(Math.toRadians(35)));
        p.add(screen("exit screen", INNER + 0.01f, top + rise, 0.34f, 35f, false));
        p.add(screen("exit screen glass", INNER + 0.01f, top + rise, 0.34f, 35f, true));
        // Central flaps and their red leading edges (these move).
        p.add(flapPart(-1, false));
        p.add(flapPart(1, false));
        p.add(edgePart(-1, false));
        p.add(edgePart(1, false));
        PARTS = Collections.unmodifiableList(p);
    }

    private GateModel() {}

    /** Screen housing (black) or its glowing glass, tilted around the x axis at the given corner. */
    private static Part screen(String name, float x, float y, float z, float tilt, boolean glass) {
        if (!glass) return new Part(name, Kind.STATIC, "BLACK_CONCRETE", x, y, z, 0.12f, 0.012f, 0.10f, tilt, false);
        // Sit the glass just above the housing's surface: offset (0.01, 0.013, 0.01) in the tilted frame.
        double a = Math.toRadians(tilt);
        float oy = (float) (0.013 * Math.cos(a) - 0.01 * Math.sin(a));
        float oz = (float) (0.013 * Math.sin(a) + 0.01 * Math.cos(a));
        return new Part(name, Kind.LIGHT, "LIGHT_BLUE_CONCRETE", x + 0.01f, y + oy, z + oz, 0.10f, 0.002f, 0.08f, tilt, true);
    }

    /** A flap panel, attached to its own cabinet. side -1 = left, +1 = right. */
    static Part flapPart(int side, boolean open) {
        float w = open ? GONE : INNER - TIP;
        float tx = side < 0 ? -INNER : INNER - w;
        return new Part(side < 0 ? "left flap" : "right flap", Kind.FLAP, "WHITE_STAINED_GLASS",
                tx, FLAP_Y, -FLAP_T / 2, w, FLAP_H, FLAP_T, 0, false);
    }

    /** The red edge on a flap's tip; when open it hides inside the cabinet. */
    static Part edgePart(int side, boolean open) {
        float tip = open ? INNER + EDGE_W : TIP;                  // distance of the tip from the middle
        float tx = side < 0 ? -tip - EDGE_W : tip;
        return new Part(side < 0 ? "left flap edge" : "right flap edge", Kind.EDGE, "RED_CONCRETE",
                tx, FLAP_Y, -FLAP_T / 2 - 0.003f, EDGE_W, FLAP_H, FLAP_T + 0.006f, 0, false);
    }

    /** The open or closed shape of a moving part (FLAP or EDGE); other parts don't change. */
    public static Part moved(int index, boolean open) {
        Part p = PARTS.get(index);
        int side = p.name().startsWith("left") ? -1 : 1;
        return switch (p.kind()) {
            case FLAP -> flapPart(side, open);
            case EDGE -> edgePart(side, open);
            default -> p;
        };
    }

    /** Snap a player's yaw to the nearest of the four directions (0 south, 90 west, 180 north, 270 east). */
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

    /** Gate coordinates (x, z) to a world offset {dx, dz}, turned the way the gate faces. */
    public static double[] toWorld(double lx, double lz, float snappedYaw) {
        double a = Math.toRadians(snappedYaw);
        double c = Math.round(Math.cos(a)), s = Math.round(Math.sin(a));
        return new double[] {lx * c - lz * s, lx * s + lz * c};
    }
}
