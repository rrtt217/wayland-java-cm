package wcm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * SDL window + GL test pattern + HDR color management done with
 * Ramblurr/wayland-java (no C shim at all).
 *
 *   gradle run --args="--mode hdr10 --10bit"
 *
 * keys: 1=hdr10 2=scrgb 3=p3 4=none  ESC=quit
 */
public final class Main {

    private static final long SDL_WINDOW_RESIZABLE = 0x0000000000000020L;

    private static final int[] MODE_BY_SCANCODE = new int[256];

    public static void main(String[] args) throws Throwable {
        String modeName = "hdr10";
        String dump = null;
        int frames = -1;
        boolean tenBit = false;
        String sdlName = System.getProperty("wcm.sdl", "libSDL3.so.0");

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode" -> modeName = args[++i];
                case "--frames" -> frames = Integer.parseInt(args[++i]);
                case "--dump" -> dump = args[++i];
                case "--10bit" -> tenBit = true;
                default -> {
                    System.err.println("usage: Main [--mode hdr10|scrgb|p3|none]"
                            + " [--frames N] [--dump file.png] [--10bit]");
                    return;
                }
            }
        }

        MODE_BY_SCANCODE[GlPattern.SCANCODE_1] = GlPattern.MODE_HDR10;
        MODE_BY_SCANCODE[GlPattern.SCANCODE_2] = GlPattern.MODE_SCRGB;
        MODE_BY_SCANCODE[GlPattern.SCANCODE_3] = GlPattern.MODE_P3;
        MODE_BY_SCANCODE[GlPattern.SCANCODE_4] = GlPattern.MODE_NONE;

        Arena arena = Arena.ofConfined();
        Sdl sdl = Sdl.load(sdlName);
        MemorySegment window = MemorySegment.NULL;
        MemorySegment context = MemorySegment.NULL;
        ColorManagement cm = null;

        try {
            setGlAttributes(sdl, tenBit);
            sdl.setHint(arena, "SDL_VIDEO_DRIVER", "wayland");
            if (!sdl.init(Sdl.INIT_VIDEO)) {
                throw new IllegalStateException("SDL_Init failed");
            }
            window = sdl.createWindow(arena, "wayland-java HDR color management",
                    800, 500, Sdl.WINDOW_OPENGL | SDL_WINDOW_RESIZABLE);
            if (window.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("SDL_CreateWindow failed");
            }
            context = sdl.glCreateContext(window);
            Gl gl = context.equals(MemorySegment.NULL) ? null : Gl.load(sdl, arena);

            MemorySegment wlDisplay = sdl.globalProperty(Sdl.PROP_GLOBAL_WL_DISPLAY, arena);
            MemorySegment wlSurface = sdl.windowProperty(window, Sdl.PROP_WINDOW_WL_SURFACE, arena);

            int mode = GlPattern.MODE_NONE;
            try {
                if (!"wayland".equals(sdl.currentVideoDriver())) {
                    throw new IllegalStateException("video driver is " + sdl.currentVideoDriver());
                }
                cm = ColorManagement.attach(wlDisplay, wlSurface, 3000);
                System.out.printf("wayland-java: bound wp_color_manager_v1, %d features%n",
                        cm.features().size());

                ColorManagement.Mode requested = ColorManagement.Mode.of(modeName);
                int rc = cm.apply(requested);
                System.out.println("apply(" + requested + ") -> " + rc + " : " + cm.message());
                if (rc == ColorManagement.OK) {
                    mode = requested.ordinal();
                } else {
                    cm.close();
                    cm = null;
                }
            } catch (Throwable t) {
                System.out.println("color management unavailable: " + t + " -> SDR");
                for (Throwable c = t.getCause(); c != null; c = c.getCause()) {
                    System.out.println("    caused by: " + c);
                }
                if (cm != null) {
                    cm.close();
                    cm = null;
                }
            }

            System.out.println("keys: 1=hdr10 2=scrgb 3=p3 4=none ESC=quit");
            long t0 = System.nanoTime();
            int i = 0;
            boolean running = true;
            MemorySegment event = arena.allocate(128);
            while (running) {
                boolean quit = false;
                while (sdl.pollEvent(event)) {
                    if (event.get(ValueLayout.JAVA_INT, 0) == Sdl.EVENT_QUIT) {
                        quit = true;
                    }
                }
                if (quit) {
                    running = false;
                }

                MemorySegment keys = sdl.keyboardState(arena);
                if (keys.get(ValueLayout.JAVA_BOOLEAN, GlPattern.SCANCODE_ESCAPE)) {
                    running = false;
                }
                if (cm != null) {
                    for (int sc = 0; sc < MODE_BY_SCANCODE.length && running; sc++) {
                        int m = MODE_BY_SCANCODE[sc];
                        if (m == 0 || m == mode) {
                            continue;
                        }
                        if (keys.get(ValueLayout.JAVA_BOOLEAN, sc)) {
                            ColorManagement.Mode want = ColorManagement.Mode.values()[m];
                            int rc = cm.apply(want);
                            System.out.println("apply(" + want + ") -> " + rc + " : " + cm.message());
                            if (rc == ColorManagement.OK) {
                                mode = m;
                            }
                        }
                    }
                }

                if (gl != null) {
                    int[] wh = sdl.windowSizeInPixels(window, arena);
                    float t = (float) ((System.nanoTime() - t0) / 1e9);
                    gl.viewport(0, 0, wh[0], wh[1]);
                    GlPattern.render(gl, wh[0], wh[1], mode, t);
                    if (dump != null && (frames < 0 || i == frames - 1)) {
                        System.out.println("dump " + dump + ": "
                                + (GlPattern.dump(sdl, gl, arena, wh[0], wh[1], dump) ? "ok" : "failed"));
                    }
                    sdl.glSwapWindow(window);
                    if (cm != null) {
                        cm.pump(0);
                    }
                } else {
                    sdl.delay(16);
                }

                i++;
                if (frames >= 0 && i >= frames) {
                    running = false;
                }
            }

            if (cm != null) {
                cm.unset();
                System.out.println("final: " + cm.message());
            }
        } finally {
            if (cm != null) {
                cm.close();
            }
            if (!context.equals(MemorySegment.NULL)) {
                sdl.glDestroyContext(context);
            }
            if (!window.equals(MemorySegment.NULL)) {
                sdl.destroyWindow(window);
            }
            sdl.quit();
            arena.close();
        }
    }

    private static void setGlAttributes(Sdl sdl, boolean tenBit) throws Throwable {
        sdl.glSetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
        sdl.glSetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 3);
        sdl.glSetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, 1);
        if (tenBit) {
            sdl.glSetAttribute(SDL_GL_RED_SIZE, 10);
            sdl.glSetAttribute(SDL_GL_GREEN_SIZE, 10);
            sdl.glSetAttribute(SDL_GL_BLUE_SIZE, 10);
            sdl.glSetAttribute(SDL_GL_ALPHA_SIZE, 2);
        }
    }

    private static final int SDL_GL_RED_SIZE = 0;
    private static final int SDL_GL_GREEN_SIZE = 1;
    private static final int SDL_GL_BLUE_SIZE = 2;
    private static final int SDL_GL_ALPHA_SIZE = 3;
    private static final int SDL_GL_CONTEXT_MAJOR_VERSION = 17;
    private static final int SDL_GL_CONTEXT_MINOR_VERSION = 18;
    private static final int SDL_GL_CONTEXT_PROFILE_MASK = 21;
}
