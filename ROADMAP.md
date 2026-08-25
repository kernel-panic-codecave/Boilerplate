# Roadmap

A first-draft milestone breakdown for Tubular Storage — a steampunk pneumatic-tube logistics and
storage mod aiming for AE2/Refined Storage-level feature completeness. This is a starting point to
revise once each milestone's actual design work begins, not a committed spec.

For implementation-level detail (blocks, block entities, network packets, GUI screens, data
structures, and how each milestone maps onto Archie/CSL's real APIs), see
[`docs/design/README.md`](docs/design/README.md) — this file stays the high-level summary.

## M1 — Pipe network core

Pipe block(s), item transport through a connected pipe network, extraction/insertion at inventory
endpoints, basic routing.

## M2 — Sorting & routing

Filter/priority modules on pipes, colour-coded routing (Logistics-Pipes-style).

## M3 — Warehouse storage

Not AE2-style abstract storage cells — a **physical warehouse multiblock**: a gantry (crane)
system that scans a player-defined area, picking/placing items into racks/shelves built from the
player's own block palette (no proprietary storage-cell block skin). The network still needs an
item index of what's stored where for search/retrieval at a terminal; evaluate reusing
`earth.terrarium.common_storage_lib` (already an Archie dependency) for that indexing layer rather
than the primary storage representation.

## M4 — Crafting automation

Pattern-based autocrafting (AE2/RS-style), terminal-driven crafting requests.

## M5 — Steampunk power layer

Pressure/steam as the mod's throughput-gating mechanic (compressors, pressure tanks) — the thing
that makes this not just a reskin.

## M6 — Polish & parity

REI integration (Archie already depends on REI), config screens via Archie's `ConfigContainer`,
wireless/remote terminal access, VFX/SFX for the pneumatic-tube feel.

## Reuse from Archie

Don't rebuild what Archie already provides:

- **Config**: `ConfigContainer`/`CategorySpec`/`DataSpec` (see Archie's `Archie.kt` `Config`
  object) for Tubular Storage's own config, instead of a bespoke config system.
- **Networking**: `NetworkChannel` for `@Serializable` packet definitions and registration.
- **GUI state syncing**: `BlockEntityStateManager`/`ItemStateManager`.
- **Dev/gametest context detection**: `AGameTestPlatform`/`Platform.isDevelopmentEnvironment()` —
  used to gate M1+'s eventual datagen/gametest code at runtime instead of splitting it into
  separate Gradle modules (see `AGENTS.md`).
