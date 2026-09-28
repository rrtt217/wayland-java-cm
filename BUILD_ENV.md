> **Note (historical):** this documents what building *upstream* wayland-java
> required, and why that toolchain was replaced. Nothing here is needed to
> build this repository — see [README.md](README.md) and
> [vendor/README.md](vendor/README.md). It is kept because the re-vendoring
> steps in `vendor/slim/regen.sh` still need jextract and the protocol XML.

# wayland_java_cm — HDR color management via Ramblurr/wayland-java

A separate experiment from `../wayland_color_management` (which uses a C shim).
Here **the whole Wayland color-management side is Java**, built directly on
[`Ramblurr/wayland-java`](https://github.com/Ramblurr/wayland-java) — no C shim,
no hand-written protocol marshalling.

## Result: it works

```
$ gradle run --args="--mode hdr10 --10bit --dump render-hdr10.png"
wayland-java: bound wp_color_manager_v1, 6 features
apply(HDR10) -> 0 : applied HDR10 (identity=0x14e1)
dump render-hdr10.png: ok
```

All four modes verified on KWin Plasma (`wp_color_manager_v1` v2):
`hdr10`, `scrgb`, `p3`, `none`; renders in `render-*.png`.

## What wayland-java gives you (and what it misses)

| | |
|---|---|
| Generated proxies | `WlDisplayProxy`, `WlRegistryProxy`, `WlSurfaceProxy`, … all have a **public `(MemorySegment)` constructor** |
| Wrap SDL's objects | `new WlDisplayProxy(sdlWlDisplay)`, `new WlSurfaceProxy(sdlWlSurface)` — no second connection needed |
| Event isolation | `Display.createQueue()` / `Proxy.setQueue()` / `prepareReadQueue` / `readEvents` / `dispatchQueuePending` |
| Color management | **Not shipped.** `wayland-protocols` is published with `withStaging = false`, so `wp_color_manager_v1` is absent. You must generate it yourself. |
| Teardown | `Proxy.destroy()` is local-only and is **overridden** by the generated `destroy()`; do not also call `wl_proxy_destroy` (double free → SIGSEGV in libwayland). |

Generate the missing protocol with your own `package-info.java` (see
`app/src/main/java/org/freedesktop/wayland/package-info.java`). The package
names are **relative to the `package-info` package**, and the generated proxies
must land in `org.freedesktop.wayland.client` because wayland-java's runtime
classes (`Proxy`, `Display`, `EventQueue`) live there:

```java
@WaylandCustomProtocols({
    @WaylandCustomProtocol(path = "wayland.xml", pkgConfig = "wayland-scanner",
        clientPackage = "client", sharedPackage = "shared", generateServer = false),
    @WaylandCustomProtocol(path = "staging/color-management/color-management-v1.xml",
        pkgConfig = "wayland-protocols",
        clientPackage = "client", sharedPackage = "shared", generateServer = false)
})
package org.freedesktop.wayland;
```

Because we generate core `wayland.xml` ourselves, we depend on
`wayland-native` + `stubs-shared` + `stubs-client` but **not** on the
`wayland-protocols` artifact (which would duplicate the core classes).

## Upstream patch required: the Java 22 lock

`wayland-java/build.gradle.kts` contains exactly one toolchain pin:

```kotlin
languageVersion.set(JavaLanguageVersion.of(22))   // -> patched to 25
```

There is no other Java-version lock, and nothing in the code is 22-specific, so
this is a one-line change. **As shipped it is a real blocker**: with only
JDK 21 and 25 installed, an unpatched checkout cannot resolve its toolchain.

## Build environment

### To build `wayland-java` + this app

| Need | Version used | Why | Packaged? |
|---|---|---|---|
| **JDK 25** (toolchain) | 25.0.4.1 | FFM is final; generated code targets 25 | yes |
| **JDK 21** (Gradle runtime) | 21.0.11-ms | **Gradle 8.10 does not run on JDK 25** | yes |
| **Gradle** | 8.10 via wrapper | downloads itself (~130 MB) | wrapper |
| **jextract** | 25 (EA) | generates `wayland-native` from libwayland headers | **no distro package — must be fetched (61 MB tar, 211 MB unpacked)** |
| **pkg-config** | — | locate `wayland.xml` / protocol XML, and libwayland for jextract | yes |
| **libwayland-client headers** | 1.25.0 | `pkg-config wayland-client` | `libwayland-dev` |
| **wayland-protocols XML** | 1.49 | staging color-management-v1 | `wayland-protocols` |
| **network** | — | Gradle dist + Maven deps | — |

Not needed, contrary to expectation:

- **`wayland-scanner` binary** — wayland-java's "wayland-scanner" module is a pure
  Java annotation processor that parses the XML itself. It only needs the XML
  files found via `pkg-config`.
- **a C compiler** for the shim (there is no shim); jextract bundles clang.

### To run

`libwayland-client.so.0`, `libSDL3.so.0`, and the generated classes on the
classpath. Nothing else.

### Two-JDK invocation

Because Gradle 8.10 cannot run on JDK 25, run Gradle with JDK 21 and let the
toolchain do the compiling:

```sh
export GRADLE_USER_HOME=$PWD/.gradle-home      # keep caches in the workspace
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-ms
export PATH="$JAVA_HOME/bin:$JEXTRACT_DIR/bin:$PATH"
./wayland-java/gradlew --no-daemon -p app \
    -Dorg.gradle.java.installations.paths=$HOME/.sdkman/candidates/java/25-redhat-local \
    run --args="--mode hdr10 --10bit"
```

Mirrors (see `.gradle-home/init.d/mirrors.gradle.kts` and
`wayland-java/gradle/wrapper/gradle-wrapper.properties`):

- Gradle distribution: `https://mirror.nju.edu.cn/gradle/` (~12 MB/s vs a
  stalling `services.gradle.org`)
- Maven/plugin repositories: `https://maven.aliyun.com/repository/{public,central,gradle-plugin}`

## Gotchas hit while writing this

1. **`poll(fds, nfds, timeout)` argument order.** Passing the timeout as `nfds`
   makes the kernel write `revents` for N pollfds into an 8-byte buffer. The
   corruption surfaces later as a JVM crash in *class loading*
   (`SymbolTable::do_lookup`), which is wildly misleading.
2. **`Proxy.destroy()` vs generated `destroy()`.** Generated `destroy()` marshals
   the wire request only; `Proxy.destroy()` frees locally only. Calling
   `wl_proxy_destroy` after a generated `destroy()` double-frees → SIGSEGV in
   `libwayland-client`.
3. **Never move SDL's `wl_surface` onto your private queue.** SDL keeps using it;
   after you destroy the queue, SDL's proxy sits on freed memory. Only pass it
   as an argument to `get_surface()` — the created
   `wp_color_management_surface_v1` inherits the manager's queue.
4. **Do not `disconnect()` a `WlDisplayProxy` wrapping SDL's pointer.**
5. `wp_image_description_creator_params_v1.create` is `type="destructor"`: the
   creator is consumed on the wire and must not be sent a destroy request.

## Files

```
wayland-java/                       patched upstream checkout (toolchain 25)
app/src/main/java/
  org/freedesktop/wayland/package-info.java   generates the two protocols
  wcm/ColorManagement.java          color management on wayland-java
  wcm/Main.java                     SDL window + GL pattern + mode switching
  wcm/Probe.java                    standalone wayland-java probe (no SDL)
  wcm/Sdl.java, Gl.java, GlPattern.java       reused from ../wayland_color_management
.gradle-home/init.d/mirrors.gradle.kts        Aliyun mirrors
BUILD_ENV.md                        this file
```

`wcm.Probe` is the isolation tool that pinned the bug down: it can use
wayland-java's own connection (`gradle probe`) or wrap SDL's display
(`gradle probe -PprobeArgs="--sdl --gl"`).
