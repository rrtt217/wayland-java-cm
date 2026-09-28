package wcm;

import org.freedesktop.wayland.client.EventQueue;
import org.freedesktop.wayland.client.WlDisplayProxy;
import org.freedesktop.wayland.client.WlRegistryEvents;
import org.freedesktop.wayland.client.WlRegistryProxy;
import org.freedesktop.wayland.client.WlSurfaceProxy;
import org.freedesktop.wayland.client.WpColorManagementSurfaceV1Events;
import org.freedesktop.wayland.client.WpColorManagementSurfaceV1Proxy;
import org.freedesktop.wayland.client.WpColorManagerV1EventsV2;
import org.freedesktop.wayland.client.WpColorManagerV1Proxy;
import org.freedesktop.wayland.client.WpImageDescriptionCreatorParamsV1Events;
import org.freedesktop.wayland.client.WpImageDescriptionCreatorParamsV1Proxy;
import org.freedesktop.wayland.client.WpImageDescriptionV1EventsV2;
import org.freedesktop.wayland.client.WpImageDescriptionV1Proxy;
import org.freedesktop.wayland.shared.WpColorManagerV1Primaries;
import org.freedesktop.wayland.shared.WpColorManagerV1RenderIntent;
import org.freedesktop.wayland.shared.WpColorManagerV1TransferFunction;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Wayland HDR color management built directly on Ramblurr/wayland-java.
 *
 * wayland-java's generated proxies all have a public {@code (MemorySegment)}
 * constructor, so SDL's raw wl_display / wl_surface can be wrapped instead of
 * opening a second Wayland connection:
 *
 *   WlDisplayProxy display = new WlDisplayProxy(sdlWlDisplay);
 *   WlSurfaceProxy surface = new WlSurfaceProxy(sdlWlSurface);
 *
 * Everything then happens on a private EventQueue (the same isolation trick the
 * C shim uses), and the wl_display is never connected or disconnected here —
 * SDL owns it.
 */
public final class ColorManagement implements AutoCloseable {

    public enum Mode {
        NONE, SCRGB, HDR10, P3;

        public static Mode of(String s) {
            return switch (s) {
                case "none" -> NONE;
                case "scrgb" -> SCRGB;
                case "p3" -> P3;
                default -> HDR10;
            };
        }
    }

    public static final int OK = 0;
    public static final int UNSUPPORTED = -1;
    public static final int FAILED = -2;
    public static final int TIMEOUT = -4;

    private static final int POLLIN = 0x001;

    /** Do not touch the display proxy: SDL owns the connection. */
    private final WlDisplayProxy display;
    /** Do not destroy the surface proxy: SDL owns the wl_surface. */
    private final WlSurfaceProxy surface;
    private final EventQueue queue;
    private final MethodHandle poll;
    private final MemorySegment pollfd;

    private WlRegistryProxy registry;
    private WpColorManagerV1Proxy cm;
    private WpColorManagementSurfaceV1Proxy cmSurface;
    private WpImageDescriptionV1Proxy desc;

    private final Set<Integer> features = new HashSet<>();
    private final Set<Integer> tfs = new HashSet<>();
    private final Set<Integer> primaries = new HashSet<>();
    private boolean featuresDone;

    private boolean descReady;
    private boolean descFailed;
    private long descIdentity;
    private String message = "";

    private ColorManagement(WlDisplayProxy display, WlSurfaceProxy surface, EventQueue queue,
                            MethodHandle poll, MemorySegment pollfd) {
        this.display = display;
        this.surface = surface;
        this.queue = queue;
        this.poll = poll;
        this.pollfd = pollfd;
    }

    public static ColorManagement attach(MemorySegment wlDisplayPtr, MemorySegment wlSurfacePtr,
                                         int timeoutMs) throws Throwable {
        WlDisplayProxy display = new WlDisplayProxy(wlDisplayPtr);
        EventQueue queue = display.createQueue();
        // NOTE: deliberately NOT surface.setQueue(queue). SDL keeps using its
        // wl_surface, and its proxy is only ever passed as an argument to
        // get_surface() -- the wp_color_management_surface_v1 is a child of the
        // manager, so it inherits the manager's queue anyway.
        WlSurfaceProxy surface = new WlSurfaceProxy(wlSurfacePtr);

        Arena arena = Arena.global();
        SymbolLookup libc = SymbolLookup.libraryLookup("libc.so.6", arena);
        MethodHandle poll = Linker.nativeLinker().downcallHandle(
                libc.find("poll").orElseThrow(() -> new UnsatisfiedLinkError("poll")),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        MemorySegment pollfd = arena.allocate(8);

        ColorManagement cm = new ColorManagement(display, surface, queue, poll, pollfd);
        cm.handshake(timeoutMs);
        if (cm.cm == null) {
            throw new IllegalStateException(cm.message.isEmpty()
                    ? "compositor does not advertise wp_color_manager_v1" : cm.message);
        }
        return cm;
    }

    private void handshake(int timeoutMs) throws Throwable {
        final WpColorManagerV1Proxy[] bound = new WpColorManagerV1Proxy[1];

        WpColorManagerV1EventsV2 cmEvents = new WpColorManagerV1EventsV2() {
            @Override public void supportedIntent(WpColorManagerV1Proxy e, int renderIntent) { }
            @Override public void supportedFeature(WpColorManagerV1Proxy e, int feature) { features.add(feature); }
            @Override public void supportedTfNamed(WpColorManagerV1Proxy e, int tf) { tfs.add(tf); }
            @Override public void supportedPrimariesNamed(WpColorManagerV1Proxy e, int p) { primaries.add(p); }
            @Override public void done(WpColorManagerV1Proxy e) { featuresDone = true; }
        };

        registry = display.getRegistry(new WlRegistryEvents() {
            @Override
            public void global(WlRegistryProxy emitter, int name, String interfaceName, int version) {
                if (WpColorManagerV1Proxy.INTERFACE_NAME.equals(interfaceName)) {
                    int v = Math.min(version, WpColorManagerV1EventsV2.VERSION);
                    bound[0] = emitter.bind(name, WpColorManagerV1Proxy.class, v, cmEvents);
                    // children (creator, description, cm-surface) inherit this queue
                    bound[0].setQueue(queue);
                }
            }

            @Override
            public void globalRemove(WlRegistryProxy emitter, int name) { }
        });
        registry.setQueue(queue);

        if (!waitUntil(() -> bound[0] != null && featuresDone, timeoutMs)) {
            message = "timed out discovering wp_color_manager_v1 (bound=" + (bound[0] != null) + ")";
            return;
        }
        cm = bound[0];
    }

    /**
     * Wait with a deadline using the prepare-read / poll / read-events /
     * dispatch-pending cycle, exactly like the C shim, so we never block
     * forever and never dispatch SDL's objects.
     */
    private boolean waitUntil(BooleanSupplier cond, int timeoutMs) throws Throwable {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            pump(50);
        }
        return cond.getAsBoolean();
    }

    /** One non-blocking-ish iteration of the queue. */
    public void pump(int timeoutMs) throws Throwable {
        if (display.prepareReadQueue(queue) == 0) {
            display.flush();
            pollfd.set(ValueLayout.JAVA_INT, 0, display.getFD());
            pollfd.set(ValueLayout.JAVA_SHORT, 4, (short) POLLIN);
            // poll(fds, nfds, timeout): exactly ONE pollfd, timeout in ms.
            int n = (int) poll.invoke(pollfd, 1L, timeoutMs);
            if (n > 0) {
                display.readEvents();
            } else {
                display.cancelRead();
            }
        }
        display.dispatchQueuePending(queue);
    }

    public boolean supportsFeature(int feature) {
        return features.contains(feature);
    }

    public boolean supportsPrimaries(int p) {
        return primaries.contains(p);
    }

    public boolean supportsTf(int tf) {
        return tfs.contains(tf);
    }

    public Set<Integer> features() {
        return features;
    }

    public String message() {
        return message;
    }

    public long descriptionIdentity() {
        return descIdentity;
    }

    public int apply(Mode mode) throws Throwable {
        if (mode == Mode.NONE) {
            unset();
            message = "unset (SDR)";
            return OK;
        }

        descReady = false;
        descFailed = false;
        descIdentity = 0;

        WpImageDescriptionV1EventsV2 descEvents = new WpImageDescriptionV1EventsV2() {
            @Override
            public void failed(WpImageDescriptionV1Proxy e, int cause, String msg) {
                descFailed = true;
                message = "image description failed (cause=" + cause + "): " + msg;
            }

            @Override
            public void ready(WpImageDescriptionV1Proxy e, int identity) {
                descReady = true;
                descIdentity = identity & 0xffffffffL;
            }

            @Override
            public void ready2(WpImageDescriptionV1Proxy e, int hi, int lo) {
                descReady = true;
                descIdentity = ((long) hi << 32) | (lo & 0xffffffffL);
            }
        };

        WpImageDescriptionV1Proxy descLocal;
        if (mode == Mode.SCRGB) {
            if (!supportsFeature(7 /* WINDOWS_SCRGB */)) {
                message = "compositor lacks the windows_scrgb feature";
                return UNSUPPORTED;
            }
            descLocal = cm.createWindowsScrgb(descEvents);
        } else {
            int tf = (mode == Mode.HDR10)
                    ? WpColorManagerV1TransferFunction.ST2084_PQ.getValue()
                    : WpColorManagerV1TransferFunction.GAMMA22.getValue();
            int prim = (mode == Mode.HDR10)
                    ? WpColorManagerV1Primaries.BT2020.getValue()
                    : WpColorManagerV1Primaries.DISPLAY_P3.getValue();

            if (!supportsTf(tf)) {
                message = "compositor does not support named tf " + tf;
                return UNSUPPORTED;
            }
            if (!supportsPrimaries(prim)) {
                message = "compositor does not support named primaries " + prim;
                return UNSUPPORTED;
            }

            WpImageDescriptionCreatorParamsV1Proxy creator =
                    cm.createParametricCreator(new WpImageDescriptionCreatorParamsV1Events() { });
            creator.setTfNamed(tf);
            creator.setPrimariesNamed(prim);
            if (mode == Mode.HDR10) {
                creator.setLuminances(5, 1000, 203);
            }
            descLocal = creator.create(descEvents);
            // create() consumed the creator on the wire (type="destructor"),
            // so it must never be sent a destroy request.
        }

        if (!waitUntil(() -> descReady || descFailed, 3000)) {
            message = "timed out waiting for image description";
            return TIMEOUT;
        }
        if (descFailed) {
            return FAILED;
        }

        if (desc != null) {           // drop the previous description
            desc.destroy();
        }
        desc = descLocal;

        if (cmSurface == null) {
            cmSurface = cm.getSurface(new WpColorManagementSurfaceV1Events() { }, surface);
            cmSurface.setQueue(queue);
        }
        cmSurface.setImageDescription(desc, WpColorManagerV1RenderIntent.PERCEPTUAL.getValue());
        display.flush();
        message = "applied " + mode + " (identity=0x" + Long.toHexString(descIdentity) + ")";
        return OK;
    }

    public void unset() throws Throwable {
        if (cmSurface != null) {
            cmSurface.unsetImageDescription();
            display.flush();
        }
    }


    @Override
    public void close() {
        // Deliberately NOT calling display.disconnect()/surface.destroy():
        // SDL owns those objects and will tear them down itself.
        try {
            // Only the protocol destroy requests: wayland-java's generated
            // destroy() already handles the local proxy for destructor-type
            // requests, and calling wl_proxy_destroy ourselves double-frees
            // (observed as a SIGSEGV inside libwayland-client).
            if (cmSurface != null) {
                cmSurface.destroy();
            }
            if (desc != null) {
                desc.destroy();
            }
            if (cm != null) {
                cm.destroy();
            }
            if (registry != null) {
                registry.destroy();
            }
            display.flush();
            // The private queue is intentionally NOT destroyed: it is cheap, the
            // process is exiting, and destroying it only produces a "proxies
            // still attached" warning.
        } catch (Throwable t) {
            // best effort
        }
    }
}
