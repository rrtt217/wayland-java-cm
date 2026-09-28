# Third-party notices

This repository redistributes the components below. Full license texts are in
`vendor/licenses/`.

## wayland-java — Apache License 2.0

Redistributed in three forms:

- as a **git submodule** (`wayland-java/`) pinned to our fork
  <https://github.com/rrtt217/wayland-java>, which is `Ramblurr/wayland-java`
  plus the two commits listed in the top-level README;
- as **source** in `vendor/slim/src` (its jextract output, reduced to the
  symbols actually used) and `vendor/gen-src` (its generated protocol stubs);
- as **bytecode** in `vendor/libs/stubs-client.jar`, `vendor/libs/stubs-shared.jar`
  and `vendor/libs/wayland-native.jar`.

Upstream: <https://github.com/Ramblurr/wayland-java>
Copyright © 2015 Erik De Rijcke; © 2024 Casey Link; portions © 2008–2012 the
Wayland authors (the generated bindings carry the protocol's own copyright
headers). Licensed under the Apache License, Version 2.0 — see
`vendor/licenses/APACHE-2.0.txt`.

The vendored copies are **modified** relative to upstream: the generated
bindings are regenerated with a reduced symbol set, the protocol stubs are
pruned to the reachable set, and `LibWayland.java` prefers the versioned
`libwayland-client.so.0` soname. See `vendor/README.md` and
`vendor/slim/regen.sh`.

## jsr305 3.0.2 — Apache License 2.0

`vendor/libs/jsr305-3.0.2.jar`, from `com.google.code.findbugs:jsr305:3.0.2`.
Only `javax.annotation.Nonnull` / `javax.annotation.Nullable` are used, and only
at compile time — it is not needed at runtime. Licensed under the Apache
License, Version 2.0 — see `vendor/licenses/APACHE-2.0.txt`.

## SLF4J API 1.7.36 — MIT License

`vendor/libs/slf4j-api-1.7.36.jar`, from `org.slf4j:slf4j-api:1.7.36`.
Required because `stubs-shared` logs through SLF4J. Copyright © 2004–2025
QOS.ch. See `vendor/licenses/slf4j-MIT.txt`
(source: <https://www.slf4j.org/license.html>).

---

`vendor/libs/` contains these unmodified Maven Central artifacts at the
versions shown. Nothing else in this repository is derived from third-party
code.
