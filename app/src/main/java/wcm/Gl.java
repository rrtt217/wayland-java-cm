package wcm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * The few OpenGL entry points the test pattern needs, resolved through
 * SDL_GL_GetProcAddress. Deliberately no libGL link dependency.
 */
public final class Gl {

    public static final int COLOR_BUFFER_BIT = 0x00004000;
    public static final int SCISSOR_TEST     = 0x00000C11;
    public static final int RGBA             = 0x00001908;
    public static final int UNSIGNED_BYTE    = 0x00001401;

    private final MethodHandle clearColor, clear, viewport, enable, scissor, readPixels;

    private Gl(MethodHandle clearColor, MethodHandle clear, MethodHandle viewport,
               MethodHandle enable, MethodHandle scissor, MethodHandle readPixels) {
        this.clearColor = clearColor;
        this.clear = clear;
        this.viewport = viewport;
        this.enable = enable;
        this.scissor = scissor;
        this.readPixels = readPixels;
    }

    public static Gl load(Sdl sdl, Arena arena) throws Throwable {
        var v = ValueLayout.JAVA_INT;
        var f = ValueLayout.JAVA_FLOAT;
        return new Gl(
                proc(sdl, arena, "glClearColor", FunctionDescriptor.ofVoid(f, f, f, f)),
                proc(sdl, arena, "glClear", FunctionDescriptor.ofVoid(v)),
                proc(sdl, arena, "glViewport", FunctionDescriptor.ofVoid(v, v, v, v)),
                proc(sdl, arena, "glEnable", FunctionDescriptor.ofVoid(v)),
                proc(sdl, arena, "glScissor", FunctionDescriptor.ofVoid(v, v, v, v)),
                proc(sdl, arena, "glReadPixels", FunctionDescriptor.ofVoid(
                        v, v, v, v, v, v, ValueLayout.ADDRESS)));
    }

    private static MethodHandle proc(Sdl sdl, Arena arena, String name, FunctionDescriptor fd)
            throws Throwable {
        MemorySegment p = sdl.glGetProcAddress(arena, name);
        if (p.equals(MemorySegment.NULL)) {
            throw new UnsatisfiedLinkError("missing GL entry point: " + name);
        }
        return Linker.nativeLinker().downcallHandle(p, fd);
    }

    public void clearColor(float r, float g, float b, float a) throws Throwable {
        clearColor.invoke(r, g, b, a);
    }

    public void clear(int mask) throws Throwable {
        clear.invoke(mask);
    }

    public void viewport(int x, int y, int w, int h) throws Throwable {
        viewport.invoke(x, y, w, h);
    }

    public void enable(int cap) throws Throwable {
        enable.invoke(cap);
    }

    public void scissor(int x, int y, int w, int h) throws Throwable {
        scissor.invoke(x, y, w, h);
    }

    public void readPixels(int x, int y, int w, int h, int format, int type, MemorySegment dst)
            throws Throwable {
        readPixels.invoke(x, y, w, h, format, type, dst);
    }
}
