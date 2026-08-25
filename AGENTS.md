# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Tubular Storage is a pipe-based logistics mod for Minecraft, inspired by the old Logistics Pipes mod with a more steampunk aesthetic — think the pneumatic tube systems old office mailrooms used. The goal is to be roughly feature-complete with storage/logistics competitors like Applied Energistics 2 (AE2) and Refined Storage (RS), though the storage layer is a physical warehouse-with-gantry design rather than AE2-style abstract storage cells — see `ROADMAP.md`.

It depends on Archie (sibling project at `../Archie`), a published library (`net.kernelpanicsoft.archie:archie-core-*`, version pinned in `gradle/libs.versions.toml`'s `archie` entry — kept on a `-SNAPSHOT` while both projects are iterating together, since a fixed/release version number gets cached more aggressively by Gradle and won't reliably pick up a same-version `publishToMavenLocal` republish; resolved from `mavenLocal()`/`maven.kernelpanicsoft.net`) providing cross-loader config (`ConfigContainer`/`CategorySpec`/`DataSpec`), networking (`NetworkChannel`), GUI state syncing, and dev/gametest context detection. Reuse those instead of rebuilding them — see `ROADMAP.md`'s "Reuse from Archie" section. After any Archie change: `cd ../Archie && ./gradlew publishToMavenLocal`, then back here `./gradlew :tubularstorage-common:compileKotlin` (or the relevant module) to pick it up — a running dev client needs a full restart too, recompiling alone doesn't hot-reload a live JVM.

Multiloader (Fabric + NeoForge, Minecraft 1.21.1) via Architectury Loom, written in Kotlin.

## Structure

One product, not Archie's three (core/datagen/gametest) — datagen and gametest code will live inside `common` as ordinary packages, gated at runtime via `Platform.isDevelopmentEnvironment()`/Archie's `AGameTestPlatform`, not as separate Gradle subprojects or companion mods. Flat layout: `common/`, `fabric/`, `neoforge/` at repo root (no `core/` nesting, since there's only one product).

Mirrors Archie's build conventions where they still apply, deliberately simplified elsewhere:

- **No `net.kernelpanicsoft.actualizer` plugin** (no cross-module Kotlin `expect`/`actual` — Archie is the cross-loader abstraction layer already; ordinary Architectury Registries/Events work directly in common code).
- **Compose is applied** (`org.jetbrains.kotlin.plugin.compose` + `org.jetbrains.compose`, both in `allprojects{}`) — required starting M2, since Archie's GUI framework (`ComposeContainerScreen`, `@Composable` functions) needs the Kotlin compiler plugin to instrument composable code, not just the runtime Archie already provides transitively. No Dokka/mkdocs though (those back Archie's public API-docs site; nothing consumes Tubular Storage's API).
- **No `maven-publish`/Reposilite** (leaf content mod, not a library other mods depend on).
- **Reuses `net.kernelpanicsoft.archie.archie-plugin`** in the fabric/neoforge modules for `${mod_id}`-style token expansion in `fabric.mod.json`/`neoforge.mods.toml` and for `runtimeLibrary`/`bundleRuntimeLibrary` (see the NeoForge classloading caveat below).
- **Keeps `modfusioner`** (merges fabric+neoforge jars into one distributable artifact).

**NeoForge + Kotlin classloading caveat**: a plain `implementation`/`modApi` dependency on a Kotlin-ecosystem library isn't reliably visible at the right point in NeoForge's mod-bus lifecycle (`forgeRuntimeLibrary` alone lands things on `MC-BOOTSTRAP`, invisible to KotlinLangForge's stdlib on `PLUGIN`). `net.kernelpanicsoft.archie.plugin.runtimeLibrary(...)`/`bundleRuntimeLibrary(...)` fix this by running the dependency through a `PatchFMLModType` artifact transform (stamps `FMLModType: GAMELIBRARY` into its manifest) and embedding it via NeoForge's native jar-in-jar mechanism (`META-INF/jars/` + `META-INF/jarjar/metadata.json`) — confirmed by inspecting `archie-core-neoforge-1.0.0.jar` directly, which is why most of Archie's own bundled Kotlin deps (kotlinx-serialization variants, Compose runtime, knbt, tomlkt, json5k) show up as `ModDiscoverer`-logged `gamelibrary` files at boot without Tubular Storage doing anything: they're physically inside Archie's jar, and nothing else on Tubular Storage's own classpath resolves a competing plain copy of them. Any *new* Kotlin-ecosystem dependency added to `neoforge/build.gradle.kts` needs the same treatment — `runtimeLibrary(...)`/`bundleRuntimeLibrary(...)`, not a plain `implementation`/`modImplementation`. Symptom if missed: `NoClassDefFoundError`/`ClassNotFoundException` on NeoForge specifically, not Fabric.

**Compose runtime's own transitive deps don't ride along automatically**: `androidx.annotation`/`androidx.collection`/`okio` are `compose.runtime`'s own transitive dependencies inside Archie's jar - real, needed at runtime (`Recomposer.<init>` touches `androidx.collection.MutableScatterSet` the moment any Compose screen opens, e.g. `TerminalHookScreen`/`SortingHookScreen`). Unlike the deps in the paragraph above, these three are *also* ordinary transitive Gradle dependencies of other things on Tubular Storage's own resolved classpath, so a second, untagged copy exists to compete with - and NeoForge's `JarSelector`/mod-dedup logic silently keeps the wrong (non-`GAMELIBRARY`) one, discarding Archie's properly-tagged embedded copy without even a warning for these three specifically (`mixinextras` losing the same fight *does* log a `JarSelector` warning - these don't). `neoforge/build.gradle.kts` declares its own `runtimeLibrary(libs.androidx.annotation)`/`runtimeLibrary(libs.androidx.collection)`/`runtimeLibrary(libs.okio)` to force the correctly-tagged copy to win - `runtimeLibrary`, not `bundleRuntimeLibrary`, since Archie's jar already embeds the real thing for a deployed environment; Tubular Storage's dev-run just needs it *visible*, not re-embedded into its own jar too. Symptom if this regresses: `NoClassDefFoundError: androidx/collection/MutableScatterSet` in `Recomposer.<init>` the moment a Compose-backed menu screen opens, NeoForge only.

**Archie/cloth-config workaround**: both `fabric`/`neoforge` modules depend on `cloth-config` even though Tubular Storage doesn't use it directly. Archie declares it only `compileOnlyApi`/"suggests", but `Archie.init()` unconditionally touches cloth-config's `ModifierKeyCode` class while initializing Archie's own config on the client, so any mod depending on Archie crashes with `NoClassDefFoundError` on boot without it present. This looks like an Archie bug (its config init shouldn't require cloth-config unless a keycode-type config value is actually declared) — remove this dependency and the "required" `cloth-config`/`cloth_config` entries in `fabric.mod.json`/`neoforge.mods.toml` once that's fixed upstream.

## Commands

- `./gradlew build` — full build (first run downloads/decompiles Minecraft via Loom; can take several minutes).
- `./gradlew tasks` — sanity-check the project graph without a full build.
- Always use `./gradlew`, not a system-installed Gradle — the wrapper is pinned to 8.12, which the Loom/Architectury plugin versions here require.

## Conventions

- **Commits**: always Conventional Commits (`feat:`, `fix:`, `refactor:`, `docs:`, `build:`, `ci:`, `chore:`, `test:`, `perf:`, `style:`, `revert:`, optionally scoped) — every commit, not just PR titles.
- **Code comments**: no inline comments narrating a change or comparing it to a previous version. Only overarching KDoc on the public surface, stating what the code does in its current form — never what it doesn't do or how it differs from before. Keep KDoc in sync with the code; a stale doc comment is a bug.

## Not set up yet

Icon/banner art, mixins, an access widener, CurseForge/Modrinth listings + `modpublisher` config, `docs.yaml`/`release-notes.yaml`/Claude Code GitHub Action workflows, `AGENTS.md`. Add these when there's an actual need, following Archie's equivalents as a template rather than inventing new conventions.
