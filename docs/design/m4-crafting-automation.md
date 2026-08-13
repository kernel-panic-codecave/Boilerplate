# M4 — Crafting Automation

See [README.md](README.md) for shared conventions and [m3-warehouse-storage.md](m3-warehouse-storage.md) for `WarehouseIndex`/the gantry job queue this builds on.

## Pattern representation

Built on vanilla recipes rather than inventing a new format: a pattern references a resolved `RecipeHolder<CraftingRecipe>` snapshot at encode time (AE2-style pattern encoding), or a manual input/output list for non-vanilla "processing" conversions:

```kotlin
@Serializable
data class Pattern(
    val inputs: List<ItemResourceDto>,      // 9 slots, shaped
    val outputs: List<ResourceStackDto>,
    val kind: PatternKind,                  // CRAFTING | PROCESSING
)
```

Stored per `PatternProviderBlockEntity` as `nbt.listField(Pattern.serializer())`. Authored via a `PatternEditorBlock` GUI mirroring a vanilla crafting-table grid (`Slots("grid", 3, 3)` + output slot); an "encode" button snapshots the current grid into a `Pattern`.

## Resolution

`CraftingRequest(target: ItemResource, amount)` resolves recursively into a **DAG**, not a tree — memoized per-resource within one resolution pass so, e.g., two outputs both needing iron ingots share one sub-request rather than double-counting. Cycle-guarded via an in-progress resource set to reject impossible/self-referential patterns cleanly.

Walk order: for each ingredient, first check current warehouse/network stock (via `WarehouseIndex`), else recursively resolve a sub-craft from another pattern. Execution walks the DAG bottom-up (topological order); leaf ingredients are pulled through the existing warehouse gantry job queue (M3).

## Physical Assembly Table

[Decision #4](README.md#resolved-architectural-decisions): a real `AssemblyTableBlockEntity` that pipes feed and which visibly processes crafts over time, rather than resolving instantly once inputs are satisfied. Fits the mailroom/industrial-process theme, and gives M5's pressure mechanic something concrete to gate via the `PressureConsumer` hook (`basePressureCost`/`onPressureTick` scale how fast a craft completes — see [m5-pressure-power.md](m5-pressure-power.md)).

## Terminal

Reuses `WarehouseTerminalBlock`'s menu with a "Craft" tab (`TabContainerPanel`):
- Searchable list with a craftable-amount preview (a `simulate = true` dry-run of the DAG).
- A request button.
- A job-status panel — status is a `@Sync`'d field on the terminal's block entity (reused sync infra, not a new packet).

Request submission itself is a small client→server `CraftingRequestPacket(resource, amount)` — an *action*, not state, so it's the one place in this milestone that needs a bespoke packet rather than `@Sync`/`observeProperty`.

## Deferred to playtesting / not blocking

- Exact pattern-editing UX (drag-from-search-results vs. requiring physical items in hand for encoding).
