package dev.sora.relay.compat;

import java.io.Serializable;

/**
 * Android replacement for {@code java.awt.Color}.
 *
 * <p>Android has no {@code java.awt}, but the Cloudburst protocol library bundled into ProtoHax uses
 * {@code java.awt.Color} on mandatory protocol paths (skins in player lists, biome definitions, camera
 * instructions, {@code SerializedSkin}'s static initialiser, ...). Without it these classes fail with
 * {@code NoClassDefFoundError} as soon as a server sends such a packet.
 *
 * <p>The ProtoHax build relocates every reference to {@code java.awt.Color} to this class (see
 * {@code .github/protohax/apply_android_patches.py}). Only the members the bundled libraries use are
 * provided, with the exact semantics of the JDK class:
 * the four constructors, {@code getRed/Green/Blue/Alpha}, {@code getRGB}, {@code equals}, {@code hashCode}
 * and {@code toString}.
 */
public final class Color implements Serializable {

    private static final long serialVersionUID = 118526816881161077L;

    /** ARGB, exactly like java.awt.Color#getRGB(). */
    private final int value;

    /** Opaque colour from 0xRRGGBB (alpha is forced to 255). */
    public Color(int rgb) {
        this.value = 0xff000000 | rgb;
    }

    /** Colour from 0xAARRGGBB when {@code hasalpha}, otherwise from 0xRRGGBB (alpha forced to 255). */
    public Color(int rgba, boolean hasalpha) {
        this.value = hasalpha ? rgba : (0xff000000 | rgba);
    }

    public Color(int r, int g, int b) {
        this(r, g, b, 255);
    }

    public Color(int r, int g, int b, int a) {
        testColorValueRange(r, g, b, a);
        this.value = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public int getRed() {
        return (value >> 16) & 0xFF;
    }

    public int getGreen() {
        return (value >> 8) & 0xFF;
    }

    public int getBlue() {
        return value & 0xFF;
    }

    public int getAlpha() {
        return (value >> 24) & 0xFF;
    }

    public int getRGB() {
        return value;
    }

    @Override
    public int hashCode() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Color && ((Color) obj).value == this.value;
    }

    @Override
    public String toString() {
        return getClass().getName() + "[r=" + getRed() + ",g=" + getGreen() + ",b=" + getBlue() + "]";
    }

    private static void testColorValueRange(int r, int g, int b, int a) {
        boolean rangeError = false;
        StringBuilder badComponentString = new StringBuilder();

        if (a < 0 || a > 255) {
            rangeError = true;
            badComponentString.append(" Alpha");
        }
        if (r < 0 || r > 255) {
            rangeError = true;
            badComponentString.append(" Red");
        }
        if (g < 0 || g > 255) {
            rangeError = true;
            badComponentString.append(" Green");
        }
        if (b < 0 || b > 255) {
            rangeError = true;
            badComponentString.append(" Blue");
        }
        if (rangeError) {
            throw new IllegalArgumentException("Color parameter outside of expected range:" + badComponentString);
        }
    }
}
