package wcm;

import org.freedesktop.wayland.client.EventQueue;
import org.freedesktop.wayland.client.WlDisplayProxy;
import org.freedesktop.wayland.client.WlRegistryEvents;
import org.freedesktop.wayland.client.WlRegistryProxy;
import org.freedesktop.wayland.client.WpColorManagerV1EventsV2;
import org.freedesktop.wayland.client.WpColorManagerV1Proxy;
import org.freedesktop.wayland.shared.WpColorManagerV1Feature;
import org.freedesktop.wayland.client.WlSurfaceProxy;

import java.lang.foreign.MemorySegment;
import java.util.Set;
import java.util.TreeSet;

/** wayland-java standalone: its OWN connection, no SDL involved. */
public final class Probe {
    public static void main(String[] args) throws Throwable {
        System.out.println("step 0: start");
        boolean useSdl = args.length > 0 && args[0].equals("--sdl");
        boolean useGl = args.length > 1 && args[1].equals("--gl");
        org.freedesktop.wayland.client.WlSurfaceProxy sdlSurface = null;
        Sdl sdl = null;
        MemorySegment sdlWindow = MemorySegment.NULL;
        MemorySegment sdlCtx = MemorySegment.NULL;
        java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined();
        WlDisplayProxy display;
        if (useSdl) {
            sdl = Sdl.load(System.getProperty("wcm.sdl", "libSDL3.so.0"));
            sdl.setHint(arena, "SDL_VIDEO_DRIVER", "wayland");
            sdl.init(Sdl.INIT_VIDEO);
            long flags = useGl ? Sdl.WINDOW_OPENGL : 0L;
            sdlWindow = sdl.createWindow(arena, "probe", 200, 120, flags);
            if (useGl) { sdlCtx = sdl.glCreateContext(sdlWindow); }
            java.lang.foreign.MemorySegment dp = sdl.globalProperty(Sdl.PROP_GLOBAL_WL_DISPLAY, arena);
            java.lang.foreign.MemorySegment sp = sdl.windowProperty(sdlWindow, Sdl.PROP_WINDOW_WL_SURFACE, arena);
            System.out.println("step 0b: sdl display=" + dp + " surface=" + sp + " gl=" + useGl);
            display = new WlDisplayProxy(dp);
            sdlSurface = new WlSurfaceProxy(sp);
        } else {
            String name = System.getenv("WAYLAND_DISPLAY");
            display = WlDisplayProxy.connect(name == null ? "wayland-0" : name);
        }
        System.out.println("step 1: connected/wrapped");
        EventQueue q = display.createQueue();

        final WpColorManagerV1Proxy[] cm = new WpColorManagerV1Proxy[1];
        final Set<Integer> features = new TreeSet<>();
        final Set<Integer> tfs = new TreeSet<>();
        final Set<Integer> prims = new TreeSet<>();
        final boolean[] done = { false };

        WpColorManagerV1EventsV2 cmEvents = new WpColorManagerV1EventsV2() {
            @Override public void supportedIntent(WpColorManagerV1Proxy e, int i) { }
            @Override public void supportedFeature(WpColorManagerV1Proxy e, int f) { features.add(f); }
            @Override public void supportedTfNamed(WpColorManagerV1Proxy e, int tf) { tfs.add(tf); }
            @Override public void supportedPrimariesNamed(WpColorManagerV1Proxy e, int p) { prims.add(p); }
            @Override public void done(WpColorManagerV1Proxy e) { done[0] = true; }
        };

        WlRegistryProxy registry = display.getRegistry(new WlRegistryEvents() {
            @Override
            public void global(WlRegistryProxy e, int name, String iface, int version) {
                if (WpColorManagerV1Proxy.INTERFACE_NAME.equals(iface)) {
                    cm[0] = e.bind(name, WpColorManagerV1Proxy.class, Math.min(version, 2), cmEvents);
                    cm[0].setQueue(q);
                }
            }
            @Override public void globalRemove(WlRegistryProxy e, int n) { }
        });
        registry.setQueue(q);
        System.out.println("step 2: registry bound");

        long deadline = System.currentTimeMillis() + 3000;
        while (!done[0] && System.currentTimeMillis() < deadline) {
            display.dispatchQueue(q);
        }
        System.out.println("step 3: done=" + done[0] + " cm=" + (cm[0] != null));
        System.out.printf("features=%s%n", features);
        System.out.printf("feature names=%s%n", features.stream()
                .map(f -> { var e = WpColorManagerV1Feature.of(f); return e == null ? String.valueOf(f) : e.name(); })
                .toList());
        System.out.printf("tfs=%s%n", tfs);
        System.out.printf("primaries=%s%n", prims);
        if (useSdl) {
            System.out.println("step 4: NOT disconnecting (SDL owns it)");
        } else {
            display.disconnect();
            System.out.println("step 4: disconnected");
        }
    }
}
