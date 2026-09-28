# Wayland HDR color management in pure Java

Implements HDR output on Wayland using
[`wp_color_manager_v1`](https://gitlab.freedesktop.org/wayland/wayland-protocols/-/tree/main/staging/color-management)
from Java — **no C shim**. It is built directly on
[Ramblurr/wayland-java](https://github.com/Ramblurr/wayland-java) and operates on
the `wl_display` / `wl_surface` that SDL creates, instead of opening a second
Wayland connection.

The usual reason to write a C shim is that SDL exposes no API for this protocol.
That is true, but not required: SDL hands out the raw pointers through
`SDL_PROP_GLOBAL_VIDEO_WAYLAND_WL_DISPLAY_POINTER` and
`SDL_PROP_WINDOW_WAYLAND_WL_SURFACE_POINTER`, wayland-java's generated proxies
have public `(MemorySegment)` constructors, so Java can drive the protocol
directly.

Status: **working**, verified on KWin Plasma against `wp_color_manager_v1` v2 —
`hdr10`, `scrgb`, `p3` and `none` all applied and rendered.

![test pattern](docs/renders/render-hdr10.png)

## Build and run

Needs a **JDK 25 toolchain** (FFM) and `libwayland-client` at runtime.

```sh
./gradlew run --args="--mode hdr10 --10bit"
```

No jextract, no annotation processor, no `pkg-config`, no network: the Wayland
side comes from the vendored library described below.

Interactive keys: `1`=hdr10 `2`=scrgb `3`=p3 `4`=none `ESC`=quit.
Other flags: `--10bit`, `--frames N`, `--dump out.png`.

Verifying the renderer against the C implementation is possible byte-for-byte
for `none`/`scrgb`/`p3`; `hdr10` differs only by the animated marker position.

## Layout

| Path | |
|---|---|
| `app/` | the application: SDL window + GL test pattern + color management |
| `vendor/` | self-contained dependency — see [vendor/README.md](vendor/README.md) |
| `consumer/` | minimal example of depending on `vendor/` from another project |
| `docs/renders/` | dumped framebuffers |
| `wayland-java/` | **git submodule**, only needed to re-vendor (see below) |
| `BUILD_ENV.md` | what the original upstream build required, and why it is gone |

Inside `app/`:

| File | |
|---|---|
| `wcm/ColorManagement.java` | the color-management protocol work |
| `wcm/Main.java` | window, render loop, runtime mode switching |
| `wcm/GlPattern.java` | the test pattern and the sRGB / linear / PQ / gamma22 encodings |
| `wcm/Sdl.java`, `wcm/Gl.java` | minimal FFM bindings for the SDL/GL calls used |
| `wcm/Probe.java` | isolation tool used to pin two bugs down (`./gradlew probe`) |

## The vendored dependency

`vendor/` contains a trimmed, self-contained build of everything needed at
runtime. It deliberately replaces the upstream build, which needs **jextract** —
a tool that no distribution packages, so it cannot be installed from a package
manager.

| | upstream | vendored |
|---|---|---|
| jextract binding | 1449 classes / 1.6 MB | **11 source files** |
| protocol stubs | 188 files / 1.3 MB | **79 source files** |
| runtime sources | 3 published modules | **26 source files** |
| build needs | jextract, Gradle, pkg-config, `wayland-protocols` | **a JDK and two Maven jars** |

Two things make this possible:

- **Only 23 Wayland functions are actually called.** `javap` over wayland-java's
  runtime jars yields the full set, so jextract is re-run with an
  `--include-function` allow-list. `vendor/slim/regen.sh` records the three
  passes and explains why `C` and `C_1` have to be generated separately.
- **152 of the 188 protocol stubs are unreachable.** `vendor/prune.py` computes
  the closure from the app plus the runtime jars and drops the rest (data
  device, input, shm, shell, subcompositor, ...).

The result is consumed as an ordinary Gradle library:

```kotlin
// inside this repo
dependencies { implementation(project(":vendor:wayland-lite")) }

// from another project
includeBuild(".../wayland-java-cm")
dependencies { implementation("org.freedesktop.wayland:wayland-lite") }
```

See [vendor/README.md](vendor/README.md) for the full recipe, alternatives, and
the two runtime traps.

### Why the submodule exists

`wayland-java/` is **not needed to build or run anything here.** It is pinned so
that re-vendoring is reproducible, and because two small patches live in the
fork rather than in `vendor/`:

- `3f03bbd` — target JDK 25 instead of 22 (upstream pins 22, which cannot be
  resolved on a machine that has only 21 and 25)
- `ccf9839` — fetch the Gradle distribution from a mirror (separable; revert it
  when building outside CN)

```sh
git submodule update --init     # only if you need to re-vendor
```

## Findings worth keeping

**The FFM path needs `libwayland-dev` on end-user machines; the C path does not.**
Upstream's generated binding calls
`SymbolLookup.libraryLookup(System.mapLibraryName("wayland-client"))`, which asks
`dlopen` for the *unversioned* `libwayland-client.so`. That symlink ships in the
`-devel` package (`wayland-devel` on Fedora, `libwayland-dev` on Debian) while
the library itself is `libwayland-client.so.0` from the runtime package, and
`dlopen` has no soname fallback — it needs an exact filename match. A C shim
records `DT_NEEDED: libwayland-client.so.0` and therefore only needs the runtime
package. Our vendored copy prefers the real soname and falls back, so it works
with only the runtime package installed.

**Two bugs that cost real time**, both of which produced wildly misleading
symptoms:

- `poll(fds, nfds, timeout)` with the timeout passed as `nfds`. The kernel then
  writes `revents` for N pollfds into an 8-byte buffer. The corruption surfaced
  much later as a JVM crash in *class loading* (`SymbolTable::do_lookup`).
- ProGuard cannot be used the way you would hope here: wayland-java resolves its
  proxy constructors reflectively and installs the libwayland dispatcher as an
  **upcall** via `MethodHandles.lookup().findVirtual(...)`. Keeping those alive
  means keeping the `client` and `raw` packages wholesale, which leaves ProGuard
  removing ~8% (152 KB → 140 KB) while the source-level pruning above removes
  80%. Rules are kept in `vendor/proguard/wcm.pro` for reference.

**ProGuard does support Java 25** (class file v69) as of 7.10.0 — provided you
pair it with `proguard-core` 9.4.0. An older core reports a misleading
`Unsupported version number [69.0] (maximum 68.65535)`.

## Requirements

| | |
|---|---|
| build | JDK 25 (toolchain), Gradle via the wrapper |
| run | JDK 22+, `libwayland-client.so.0`, `libSDL3.so.0` |
| graphics | a Wayland session with `wp_color_manager_v1`; falls back to SDR otherwise |

If the compositor lacks the protocol, or the platform is not Wayland, the app
reports why and renders SDR instead of failing.

## License

**Apache License 2.0** — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

The vendored tree is predominantly Apache-2.0 (wayland-java, jsr305) with MIT
parts (SLF4J, and the bindings generated from wayland-protocols), so the
`wayland-lite` artifact is Apache-2.0. Full third-party license texts are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and `vendor/licenses/`.

This does not restrict reuse: Apache-2.0 code can be consumed by MIT, BSD, GPL
and proprietary projects alike, and it carries an explicit patent grant.

## Build layout: Groovy root, Kotlin subprojects

Gradle picks the DSL per script **file**, so a Groovy root and Kotlin
subprojects coexist happily — the Groovy `allprojects`/`subprojects` blocks
configure the `.kts` subprojects like any other:

```
settings.gradle            Groovy   includes :vendor:wayland-lite and :app
build.gradle               Groovy   shared config + vendorSummary / verifyLicenses
vendor/wayland-lite/build.gradle.kts  Kotlin
app/build.gradle.kts                  Kotlin
consumer/                             separate build, Kotlin
```

The one hard rule: a single directory must not contain **both** `build.gradle`
and `build.gradle.kts` (or both settings variants). There is no error and no
warning — Gradle silently uses the Groovy one and ignores the other.
