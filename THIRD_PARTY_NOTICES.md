# Third-party notices

This repository redistributes third-party **source**. Nothing is vendored as a
binary: `vendorSummary` reports `local jars: 0` for a clean tree.

## wayland-java — Apache License 2.0

Redistributed in two forms:

- as a **git submodule** (`wayland-java/`) pinned to our fork
  <https://github.com/rrtt217/wayland-java>;
- as **source**, under `vendor/`:
  - `vendor/slim/src` — its jextract output, regenerated with a reduced symbol set;
  - `vendor/gen-src` — its generated protocol stubs, pruned to the reachable set;
  - `vendor/wayland-java-src/{shared,client}` — the runtime sources actually used
    (one of them, `MessageMeta.java`, carries our fix — see below).

Upstream: <https://github.com/Ramblurr/wayland-java>
Copyright © 2015 Erik De Rijcke; © 2024 Casey Link. Licensed under the Apache
License, Version 2.0 — see [LICENSE](LICENSE).

**These copies are modified** with respect to upstream, as required to be stated
by Apache-2.0 §4(b): the jextract bindings are regenerated against an allow-list
of 23 functions, the generated protocol stubs are pruned from 197 files to 79,
`LibWayland.java` resolves the versioned `libwayland-client.so.0` soname instead
of the unversioned name, and `MessageMeta.java` rebuilds `wl_message.types`
against the message signature rather than copying `@Message.types` verbatim
instead of the unversioned name. See [vendor/README.md](vendor/README.md) and
`vendor/slim/regen.sh`.

## wayland-protocols — MIT License

`vendor/gen-src` is generated from the wayland-protocols XML, so each file
carries the protocol's copyright and permission notice inline. Copyright 2019
Sebastian Wick; 2019 Erwin Burema; 2020 AMD; 2020–2024 Collabora Ltd.; 2024
Xaver Hugl; 2022–2025 Red Hat Inc. The notice embedded in the generated files
is the MIT license text; a copy is in
[vendor/licenses/wayland-protocols-MIT.txt](vendor/licenses/wayland-protocols-MIT.txt).

Upstream: <https://gitlab.freedesktop.org/wayland/wayland-protocols>

## Resolved from Maven — not redistributed here

These are ordinary dependencies, fetched by the build and by consumers. Their
licenses apply to whoever resolves them; no copy is shipped in this repository.

| Coordinate | License |
|---|---|
| `org.slf4j:slf4j-api:1.7.36` | MIT |
| `com.google.code.findbugs:jsr305:3.0.2` | Apache License 2.0 |

NOTE: wayland-java itself is **not** on Maven Central (all coordinates 404), which
is why its sources are vendored rather than referenced.
