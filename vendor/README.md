# vendor/ — how a new project depends on this

Everything here is source + a few small jars. **No jextract, no Gradle plugin,
no annotation processor, no pkg-config, no network** is needed to consume it.

```
vendor/
  wayland-lite/   the library project (see below) — this is what you depend on
  slim/src/       13 .java  jextract output for libwayland + 6 libc calls
  gen-src/        36 .java  protocol stubs: wayland.xml + staging color-management-v1
  libs/           5 jars    stubs-shared, stubs-client, wayland-native.jar,
                            slf4j-api, jsr305
  proguard/       optional keep-rules (see the bottom of this file)
  prune.py        regenerates the reachable-class list for gen-src
```

## Recommended: a composite build

`vendor/wayland-lite/build.gradle.kts` compiles `slim/src` + `gen-src` and
exposes them as a normal `java-library` (`org.freedesktop.wayland:wayland-lite`).
It does not copy the sources — the source set points at `../slim/src` and
`../gen-src`, so there is exactly one copy in git.

New project, two files:

```kotlin
// settings.gradle.kts
includeBuild("../path/to/wayland_java_cm/vendor/wayland-lite")
```

```kotlin
// build.gradle.kts
plugins { application }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

dependencies {
    implementation("org.freedesktop.wayland:wayland-lite")
}

application {
    mainClass.set("demo.Demo")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
```

That is the whole wiring. Gradle substitutes the coordinate for the included
build, so nothing is published and no jar is committed twice.

Verified:

```
$ gradle -p consumer run
wl_display / wl_registry / wp_color_manager_v1 / PQ=11
```

Transitively you get `stubs-shared`, `stubs-client` and `slf4j-api`.
`jsr305` is `compileOnly` inside the library and does **not** leak to consumers.
FFM requires `--enable-native-access=ALL-UNNAMED` (or an `Enable-Native-Access`
manifest entry).

## Alternatives

**Plain javac / any build system** — treat the five jars as an ordinary
classpath and add the compiled output of `slim/src` + `gen-src`:

```sh
javac -d out -cp "vendor/libs/*" $(find vendor/slim/src vendor/gen-src -name '*.java')
java --enable-native-access=ALL-UNNAMED -cp "out:vendor/libs/*" demo.Demo
```

Note `vendor/libs/wayland-native.jar` is the *prebuilt* slim binding; if you
compile `vendor/slim/src` yourself you can drop that jar. Committing the sources
(and not the jar) is what makes the binding auditable and architecture
independent — the layouts are resolved at runtime via
`Linker.nativeLinker().canonicalLayouts()`, so the same sources work on
x86_64/aarch64.

**Publish to a repo inside the tree** — if you prefer coordinates over
`includeBuild`, run `gradle -p vendor/wayland-lite publish` with a
`maven { url = uri("../vendor/repo") }` repository and commit `vendor/repo`.
Composite build is simpler and keeps versions out of the picture.

**Do not use `flatDir`** — it drops transitive dependencies, so you would have
to list all five jars by hand at every call site.

## Runtime requirements

| Need | Why |
|---|---|
| JDK 22+ (we use 25) | FFM is final; the bindings use `canonicalLayouts()` |
| `libwayland-client.so.0` | the actual Wayland client |

One non-obvious trap: the binding resolves the library with
`System.mapLibraryName("wayland-client")` → **`libwayland-client.so`**, i.e. the
*unversioned* name. On a machine that has only the runtime package
(`libwayland-client.so.0`) and not the `-dev` package with its `.so` symlink,
loading fails even though Wayland itself works. Install `libwayland-dev` or
create the symlink.

## Regenerating

- `slim/src` — `vendor/slim/regen.sh` (documents the three jextract passes and
  why the `C` / `C_1` split has to be forced).
- `gen-src` — re-run the annotation processor, then `python3 vendor/prune.py`
  to drop the unreachable protocol stubs (188 files → 36).

## ProGuard (optional, and usually not worth it)

`vendor/proguard/wcm.pro` shrinks an application jar. It works on Java 25, but
only saves ~8% here (152 KB → 140 KB) because wayland-java's reflection and
method-handle upcall sites force keeping the `raw` and `client` packages
wholesale. The source-level pruning above is what actually removes the unused
protocol stubs. Keep the rules around in case you need a slim release jar; read
the comments in that file before changing them.
