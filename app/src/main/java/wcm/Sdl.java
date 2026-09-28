package wcm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Absolutely minimal hand-written FFM bindings for the handful of SDL3
 * functions this demo needs. Not a general SDL binding — if you already use
 * LWJGL's org.lwjgl.sdl, drop this class and pass the pointers from there.
 */
public final class Sdl {

    public static final int INIT_VIDEO      = 0x00000020;
    public static final long WINDOW_OPENGL  = 0x0000000000000002L;

    public static final int PIXELFORMAT_RGBA32 = 0x16762004; // ABGR8888 on little-endian
    public static final int EVENT_QUIT         = 0x100;
    public static final int SDL_NUM_SCANCODES  = 512;

    public static final String PROP_GLOBAL_WL_DISPLAY = "SDL.video.wayland.wl_display";
    public static final String PROP_WINDOW_WL_SURFACE = "SDL.window.wayland.surface";

    private final MethodHandle SDL_SetHint, SDL_Init, SDL_CreateWindow, SDL_GL_CreateContext,
            SDL_GL_DestroyContext, SDL_GL_SwapWindow, SDL_GL_GetProcAddress,
            SDL_GetGlobalProperties, SDL_GetWindowProperties, SDL_GetPointerProperty,
            SDL_PumpEvents, SDL_Delay, SDL_DestroyWindow, SDL_Quit,
            SDL_GetWindowSizeInPixels, SDL_GetKeyboardState, SDL_PollEvent,
            SDL_CreateSurfaceFrom, SDL_DestroySurface, SDL_SavePNG, SDL_GL_SetAttribute,
            SDL_GetCurrentVideoDriver;

    private Sdl(SymbolLookup lib) {
        SDL_SetHint = down(lib, "SDL_SetHint",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        SDL_Init = down(lib, "SDL_Init",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT));
        SDL_CreateWindow = down(lib, "SDL_CreateWindow",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
        SDL_GL_CreateContext = down(lib, "SDL_GL_CreateContext",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        SDL_GL_DestroyContext = down(lib, "SDL_GL_DestroyContext",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        SDL_GL_SwapWindow = down(lib, "SDL_GL_SwapWindow",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));
        SDL_GL_GetProcAddress = down(lib, "SDL_GL_GetProcAddress",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        SDL_GetGlobalProperties = down(lib, "SDL_GetGlobalProperties",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        SDL_GetWindowProperties = down(lib, "SDL_GetWindowProperties",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        SDL_GetPointerProperty = down(lib, "SDL_GetPointerProperty",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        SDL_PumpEvents = down(lib, "SDL_PumpEvents", FunctionDescriptor.ofVoid());
        SDL_Delay = down(lib, "SDL_Delay", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));
        SDL_DestroyWindow = down(lib, "SDL_DestroyWindow", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        SDL_Quit = down(lib, "SDL_Quit", FunctionDescriptor.ofVoid());
        SDL_GetWindowSizeInPixels = down(lib, "SDL_GetWindowSizeInPixels",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        SDL_GetKeyboardState = down(lib, "SDL_GetKeyboardState",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        SDL_PollEvent = down(lib, "SDL_PollEvent",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));
        SDL_CreateSurfaceFrom = down(lib, "SDL_CreateSurfaceFrom",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        SDL_DestroySurface = down(lib, "SDL_DestroySurface",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        SDL_SavePNG = down(lib, "SDL_SavePNG",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        SDL_GL_SetAttribute = down(lib, "SDL_GL_SetAttribute",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        SDL_GetCurrentVideoDriver = down(lib, "SDL_GetCurrentVideoDriver",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
    }

    private static MethodHandle down(SymbolLookup lib, String name, FunctionDescriptor fd) {
        var addr = lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError("missing SDL symbol: " + name));
        return Linker.nativeLinker().downcallHandle(addr, fd);
    }

    /**
     * Load libSDL3 for the JVM lifetime ({@link Arena#global()}), so it is never
     * dlclose()d at runtime. The per-call {@code Arena} arguments below are only
     * for transient C-string allocations.
     */
    public static Sdl load(String soname) {
        SymbolLookup lookup = SymbolLookup.libraryLookup(soname, Arena.global());
        return new Sdl(lookup);
    }

    public boolean setHint(Arena arena, String name, String value) throws Throwable {
        return (boolean) SDL_SetHint.invoke(arena.allocateFrom(name), arena.allocateFrom(value));
    }

    public boolean init(int flags) throws Throwable {
        return (boolean) SDL_Init.invoke(flags);
    }

    /** e.g. "wayland", "x11", "windows", "cocoa"; null if video is not initialized. */
    public String currentVideoDriver() throws Throwable {
        MemorySegment p = (MemorySegment) SDL_GetCurrentVideoDriver.invoke();
        if (p.equals(MemorySegment.NULL)) {
            return null;
        }
        return p.reinterpret(Long.MAX_VALUE).getString(0);
    }

    public MemorySegment createWindow(Arena arena, String title, int w, int h, long flags) throws Throwable {
        return (MemorySegment) SDL_CreateWindow.invoke(arena.allocateFrom(title), w, h, flags);
    }

    public MemorySegment glCreateContext(MemorySegment window) throws Throwable {
        return (MemorySegment) SDL_GL_CreateContext.invoke(window);
    }

    /** Must be called before {@link #glCreateContext}. */
    public boolean glSetAttribute(int attr, int value) throws Throwable {
        return (boolean) SDL_GL_SetAttribute.invoke(attr, value);
    }

    public void glDestroyContext(MemorySegment gl) throws Throwable {
        SDL_GL_DestroyContext.invoke(gl);
    }

    public boolean glSwapWindow(MemorySegment window) throws Throwable {
        return (boolean) SDL_GL_SwapWindow.invoke(window);
    }

    public MemorySegment glGetProcAddress(Arena arena, String name) throws Throwable {
        return (MemorySegment) SDL_GL_GetProcAddress.invoke(arena.allocateFrom(name));
    }

    public MemorySegment globalProperty(String name, Arena arena) throws Throwable {
        int props = (int) SDL_GetGlobalProperties.invoke();
        return (MemorySegment) SDL_GetPointerProperty.invoke(props, arena.allocateFrom(name), MemorySegment.NULL);
    }

    public MemorySegment windowProperty(MemorySegment window, String name, Arena arena) throws Throwable {
        int props = (int) SDL_GetWindowProperties.invoke(window);
        return (MemorySegment) SDL_GetPointerProperty.invoke(props, arena.allocateFrom(name), MemorySegment.NULL);
    }

    /** @return {width, height} in pixels */
    public int[] windowSizeInPixels(MemorySegment window, Arena arena) throws Throwable {
        MemorySegment w = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment h = arena.allocate(ValueLayout.JAVA_INT);
        SDL_GetWindowSizeInPixels.invoke(window, w, h);
        return new int[] { w.get(ValueLayout.JAVA_INT, 0), h.get(ValueLayout.JAVA_INT, 0) };
    }

    /** @return pointer to the bool[SDL_NUM_SCANCODES] state array, sized for indexing */
    public MemorySegment keyboardState(Arena arena) throws Throwable {
        MemorySegment numkeys = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment p = (MemorySegment) SDL_GetKeyboardState.invoke(numkeys);
        if (p.equals(MemorySegment.NULL)) {
            return MemorySegment.NULL;
        }
        int n = numkeys.get(ValueLayout.JAVA_INT, 0);
        return p.reinterpret(Math.max(n, SDL_NUM_SCANCODES));
    }

    public boolean pollEvent(MemorySegment eventBuffer) throws Throwable {
        return (boolean) SDL_PollEvent.invoke(eventBuffer);
    }

    public MemorySegment createSurfaceFrom(int w, int h, int format, MemorySegment pixels, int pitch)
            throws Throwable {
        return (MemorySegment) SDL_CreateSurfaceFrom.invoke(w, h, format, pixels, pitch);
    }

    public void destroySurface(MemorySegment surface) throws Throwable {
        SDL_DestroySurface.invoke(surface);
    }

    public boolean savePng(MemorySegment surface, Arena arena, String path) throws Throwable {
        return (boolean) SDL_SavePNG.invoke(surface, arena.allocateFrom(path));
    }

    public void delay(int ms) throws Throwable {
        SDL_Delay.invoke(ms);
    }

    public void pumpEvents() throws Throwable {
        SDL_PumpEvents.invoke();
    }

    public void destroyWindow(MemorySegment window) throws Throwable {
        SDL_DestroyWindow.invoke(window);
    }

    public void quit() throws Throwable {
        SDL_Quit.invoke();
    }
}
