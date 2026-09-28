// Vendored, dependency-free build of the pieces wayland-java needs at runtime.
// No jextract, no annotation processor, no pkg-config, no network.
plugins { `java-library` }

group = "org.freedesktop.wayland"
version = "1.0-vendored"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

// One copy of the sources, kept where they are (no duplication in git):
//   ../slim/src  jextract output for libwayland + the few libc calls (13 files)
//   ../gen-src   protocol stubs: wayland.xml + staging color-management-v1 (36 files)
sourceSets.main {
    java.srcDirs("../slim/src", "../gen-src")
}

dependencies {
    // wayland-java runtime (Proxy / Display / EventQueue / EventQueue / util)
    api(files("../libs/stubs-shared.jar", "../libs/stubs-client.jar"))
    // stubs-shared logs through slf4j
    api(files("../libs/slf4j-api-1.7.36.jar"))
    // javax.annotation.* appears in the generated sources; CLASS retention,
    // compile-only, consumers do not need it.
    compileOnly(files("../libs/jsr305-3.0.2.jar"))
}
