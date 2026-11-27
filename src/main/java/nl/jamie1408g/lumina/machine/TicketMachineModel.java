package nl.jamie1408g.lumina.machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ticket machine, modelled on a Singapore MRT General Ticketing Machine: a white kiosk about
 * 0.9 m wide and 2 m tall with a Lumina-blue header, a touch screen leaning back slightly, a card
 * reader with a yellow target (green when something is paid), a note slot, a card tray at knee
 * height and blue light strips down the front edges.
 *
 * Coordinates are the machine's own: the middle of the block's floor is (0, 0, 0), x runs across,
 * y up, and the front faces -z (towards the admin who placed it). Parts are boxes: corner offset,
 * size, then an optional tilt (degrees, around the x axis through the corner). Built from display
 * entities, so no resource pack is needed.
 */
public final class TicketMachineModel {

    /** Bump when the model changes; /lumina machine rebuild updates old machines. */
    public static final int VERSION = 1;

    public enum Kind { STATIC, LIGHT }

    public record Part(String name, Kind kind, String material, float tx, float ty, float tz,
                       float sx, float sy, float sz, float tiltDeg, boolean glow) {}

    public static final String OK = "LIME_CONCRETE";
    public static final String NO = "RED_CONCRETE";

    static final float FRONT = -0.12f;  // front face of the body
    static final float TOP = 1.80f;     // top of the body, the header sits on it

    /** Where the name on the header goes (machine coordinates), and its size. */
    public static final float LABEL_Y = 1.83f, LABEL_Z = -0.152f, LABEL_SCALE = 0.33f;

    public static final List<Part> PARTS;

    static {
        List<Part> p = new ArrayList<>();
        p.add(new Part("plinth", Kind.STATIC, "GRAY_CONCRETE", -0.45f, 0f, FRONT - 0.01f, 0.9f, 0.08f, 0.52f, 0, false));
        p.add(new Part("body", Kind.STATIC, "WHITE_CONCRETE", -0.45f, 0.08f, FRONT, 0.9f, TOP - 0.08f, 0.5f, 0, false));
        p.add(new Part("header", Kind.STATIC, "LIGHT_BLUE_CONCRETE", -0.46f, TOP, FRONT - 0.02f, 0.92f, 0.2f, 0.54f, 0, true));
        p.add(new Part("header sign", Kind.STATIC, "WHITE_CONCRETE", -0.38f, TOP + 0.03f, FRONT - 0.026f, 0.76f, 0.14f, 0.004f, 0, true));
        // Light strips down both front edges.
        p.add(new Part("left strip", Kind.LIGHT, "LIGHT_BLUE_CONCRETE", -0.45f, 0.1f, FRONT - 0.005f, 0.03f, TOP - 0.12f, 0.005f, 0, true));
        p.add(new Part("right strip", Kind.LIGHT, "LIGHT_BLUE_CONCRETE", 0.42f, 0.1f, FRONT - 0.005f, 0.03f, TOP - 0.12f, 0.005f, 0, true));
        // Touch screen, leaning back 10 degrees, with glowing glass just in front of its housing.
        p.add(new Part("screen housing", Kind.STATIC, "BLACK_CONCRETE", -0.3f, 1.18f, FRONT - 0.04f, 0.6f, 0.44f, 0.04f, 10f, false));
        p.add(new Part("screen", Kind.STATIC, "LIGHT_BLUE_CONCRETE", -0.27f, 1.21f, FRONT - 0.046f, 0.54f, 0.38f, 0.004f, 10f, true));
        p.add(new Part("screen bar", Kind.STATIC, "WHITE_CONCRETE", -0.27f, 1.52f, FRONT - 0.0475f + 0.055f, 0.54f, 0.05f, 0.004f, 10f, true));
        // Card reader (right) and note slot (left), under the screen.
        p.add(new Part("card reader", Kind.STATIC, "BLACK_CONCRETE", 0.1f, 0.96f, FRONT - 0.03f, 0.26f, 0.16f, 0.03f, 0, false));
        p.add(new Part("reader light", Kind.LIGHT, "YELLOW_CONCRETE", 0.13f, 0.99f, FRONT - 0.035f, 0.2f, 0.1f, 0.005f, 0, true));
        p.add(new Part("note slot", Kind.STATIC, "GRAY_CONCRETE", -0.36f, 1.0f, FRONT - 0.02f, 0.3f, 0.08f, 0.02f, 0, false));
        p.add(new Part("note slot opening", Kind.STATIC, "BLACK_CONCRETE", -0.33f, 1.03f, FRONT - 0.0205f, 0.24f, 0.02f, 0.001f, 0, false));
        // Card tray at knee height.
        p.add(new Part("tray opening", Kind.STATIC, "BLACK_CONCRETE", -0.2f, 0.44f, FRONT - 0.002f, 0.4f, 0.18f, 0.002f, 0, false));
        p.add(new Part("tray lip", Kind.STATIC, "LIGHT_GRAY_CONCRETE", -0.22f, 0.41f, FRONT - 0.07f, 0.44f, 0.04f, 0.07f, 0, false));
        // Status light at the top right of the front.
        p.add(new Part("status light", Kind.LIGHT, "LIME_CONCRETE", 0.32f, 1.7f, FRONT - 0.005f, 0.06f, 0.05f, 0.005f, 0, true));
        PARTS = Collections.unmodifiableList(p);
    }

    private TicketMachineModel() {}

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
