package wcm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The HDR test pattern: a linear ramp that goes to 4x SDR white, a column of
 * luminance patches, and an RGB strip. Each mode encodes those linear scene
 * values for the framebuffer the corresponding Wayland image description
 * declares:
 *
 *   NONE   no description at all -> compositor assumes sRGB, so encode sRGB
 *   SCRGB  extended linear sRGB  -> write linear values
 *   HDR10  BT2020 + ST2084 PQ    -> apply the PQ curve at absolute luminance
 *   P3     Display-P3 + gamma22  -> gamma 2.2
 *
 * The point: in sRGB everything at/above SDR white clips to white, while in
 * HDR10 the PQ curve carries 4x SDR white as a distinct code value.
 */
public final class GlPattern {

    public static final int MODE_NONE  = 0;
    public static final int MODE_SCRGB = 1;
    public static final int MODE_HDR10 = 2;
    public static final int MODE_P3    = 3;

    public static final float SDR_WHITE_NITS = 203.0f;

    public static final int SCANCODE_ESCAPE = 41;
    public static final int SCANCODE_1 = 30;
    public static final int SCANCODE_2 = 31;
    public static final int SCANCODE_3 = 32;
    public static final int SCANCODE_4 = 33;

    private static final float[] LEVELS = { 0.05f, 0.18f, 0.5f, 1.0f, 2.0f, 4.0f };

    private GlPattern() {}

    static float clampf(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    static float srgbOetf(float v) {
        v = Math.max(0f, v);
        return v <= 0.0031308f ? 12.92f * v : (float) (1.055 * Math.pow(v, 1.0 / 2.4) - 0.055);
    }

    static float gamma22Oetf(float v) {
        return (float) Math.pow(Math.max(0f, v), 1.0 / 2.2);
    }

    /** ST2084 / PQ OETF, absolute luminance in cd/m^2. */
    static float pqOetf(float lum) {
        final float m1 = 0.1593017578125f, m2 = 78.84375f;
        final float c1 = 0.8359375f, c2 = 18.8515625f, c3 = 18.6875f;
        float y = Math.max(0f, lum) / 10000.0f;
        float ym1 = (float) Math.pow(y, m1);
        return (float) Math.pow((c1 + c2 * ym1) / (1f + c3 * ym1), m2);
    }

    static void setColor(Gl gl, float r, float g, float b, int mode) throws Throwable {
        float ro, go, bo;
        switch (mode) {
            case MODE_SCRGB -> { ro = clampf(r); go = clampf(g); bo = clampf(b); }
            case MODE_HDR10 -> {
                ro = pqOetf(r * SDR_WHITE_NITS);
                go = pqOetf(g * SDR_WHITE_NITS);
                bo = pqOetf(b * SDR_WHITE_NITS);
            }
            case MODE_P3 -> { ro = clampf(gamma22Oetf(r)); go = clampf(gamma22Oetf(g)); bo = clampf(gamma22Oetf(b)); }
            default -> { ro = clampf(srgbOetf(r)); go = clampf(srgbOetf(g)); bo = clampf(srgbOetf(b)); }
        }
        gl.clearColor(ro, go, bo, 1f);
    }

    static void fillRect(Gl gl, int x, int y, int w, int h, float r, float g, float b, int mode)
            throws Throwable {
        if (w <= 0 || h <= 0) {
            return;
        }
        setColor(gl, r, g, b, mode);
        gl.scissor(x, y, w, h);
        gl.clear(Gl.COLOR_BUFFER_BIT);
    }

    public static void render(Gl gl, int w, int h, int mode, float t) throws Throwable {
        final int ncol = 120;
        int gradW = (int) (w * 0.62f);
        int patX = gradW;
        int patW = w - gradW;
        int botH = (int) (h * 0.28f);
        int topY = botH;
        int topH = h - botH;

        gl.enable(Gl.SCISSOR_TEST);

        fillRect(gl, 0, 0, w, h, 0f, 0f, 0f, mode);                       // background

        for (int i = 0; i < ncol; i++) {                                  // 0 -> 4.0 ramp
            float v = 4.0f * i / (ncol - 1);
            int x0 = gradW * i / ncol;
            int x1 = gradW * (i + 1) / ncol;
            fillRect(gl, x0, topY, x1 - x0, topH, v, v, v, mode);
        }

        for (int i = 0; i < LEVELS.length; i++) {                         // luminance patches
            int py = topY + topH - (i + 1) * topH / LEVELS.length;
            int ph = topH / LEVELS.length;
            float v = LEVELS[i];
            fillRect(gl, patX, py, patW / 2, ph, v, v, v, mode);
        }
        for (int i = 0; i < 2; i++) {                                     // SDR white / black
            int py = topY + topH - (i + 1) * topH / 2;
            int ph = topH / 2;
            float v = (i == 0) ? 1.0f : 0.0f;
            fillRect(gl, patX + patW / 2, py, patW / 2, ph, v, v, v, mode);
        }

        fillRect(gl, 0, 0, w / 3, botH, 1f, 0f, 0f, mode);                // RGB strip
        fillRect(gl, w / 3, 0, w / 3, botH, 0f, 1f, 0f, mode);
        fillRect(gl, 2 * w / 3, 0, w - 2 * w / 3, botH, 0f, 0f, 1f, mode);

        int mx = (int) ((0.5f + 0.45f * Math.sin(t)) * (gradW - 8));      // moving marker
        fillRect(gl, mx, 0, 4, h, 1f, 1f, 1f, mode);

        gl.scissor(0, 0, w, h);
    }

    /** Read the framebuffer back and write a PNG (flipped right way up). */
    public static boolean dump(Sdl sdl, Gl gl, Arena arena, int w, int h, String path)
            throws Throwable {
        int stride = w * 4;
        MemorySegment raw = arena.allocate((long) stride * h);
        MemorySegment flip = arena.allocate((long) stride * h);
        gl.readPixels(0, 0, w, h, Gl.RGBA, Gl.UNSIGNED_BYTE, raw);
        for (int y = 0; y < h; y++) {
            MemorySegment.copy(raw, (long) y * stride, flip, (long) (h - 1 - y) * stride, stride);
        }
        MemorySegment surf = sdl.createSurfaceFrom(w, h, Sdl.PIXELFORMAT_RGBA32, flip, stride);
        if (surf.equals(MemorySegment.NULL)) {
            return false;
        }
        boolean ok = sdl.savePng(surf, arena, path);
        sdl.destroySurface(surf);
        return ok;
    }
}
