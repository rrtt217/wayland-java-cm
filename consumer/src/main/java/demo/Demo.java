package demo;

import org.freedesktop.wayland.client.WlDisplayProxy;
import org.freedesktop.wayland.client.WlRegistryProxy;
import org.freedesktop.wayland.client.WpColorManagerV1Proxy;
import org.freedesktop.wayland.shared.WpColorManagerV1TransferFunction;

public final class Demo {
    public static void main(String[] args) {
        // touching generated protocol stubs + wayland-java runtime + an enum
        System.out.println(WlDisplayProxy.INTERFACE_NAME
                + " / " + WlRegistryProxy.INTERFACE_NAME
                + " / " + WpColorManagerV1Proxy.INTERFACE_NAME
                + " / PQ=" + WpColorManagerV1TransferFunction.ST2084_PQ.getValue());
    }
}
