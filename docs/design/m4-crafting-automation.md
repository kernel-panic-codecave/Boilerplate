# M4 — Crafting Automation

See [README.md](README.md) for shared conventions and [m3-warehouse-storage.md](m3-warehouse-storage.md) for `WarehouseIndex`/the gantry job queue this builds on.

## Pattern representation

Built on vanilla recipes rather than inventing a new format: a `Pattern` is a snapshot taken at encode time (AE2-style pattern encoding, not a live `RecipeHolder<CraftingRecipe>` reference - a later game/recipe change should never retroactively invalidate an already-encoded pattern), or a manual input/output list for non-vanilla "processing" conversions:

```kotlin
data class Pattern(
    val inputs: List<SItemResource>,               // up to 9, one per grid cell for CRAFTING (positional), an unordered bag for PROCESSING
    val outputs: List<SResourceStack<SItemResource>>,
    val kind: PatternKind,                          // CRAFTING | PROCESSING
)
```

No separate per-input quantity field - a resource's own required count is just how many of the 9 `inputs` entries hold it (`Pattern.requiredInputs()`), the same "count via slot occupancy" shape a real crafting grid already has.

No separate `PatternProviderBlockEntity`/`PatternEditorBlock` pair after all - `AssemblyTableBlockEntity` (see "Physical Assembly Table" below) is both: it stores `patterns: MutableList<Pattern>` (`nbt.listField(Pattern.serializer())`) *and* owns the one shared `grid`/`output` slot pair (`AssemblyTableMenu`, a vanilla-crafting-table-shaped `Slots("grid", 3, 3)` + `Slots("output", 1, 1)`) used both to manually author a new pattern (an "Encode" button, `EncodeAssemblyPatternPacket` → `PatternEncoder.encode`) and, later, to run an already-encoded one. `PatternEncoder.encode` picks `CRAFTING` if the grid currently matches a real vanilla recipe (assembling the *actual* result via `RecipeManager.getRecipeFor`/`CraftingRecipe.assemble`, not whatever happens to be sitting in `output`) and falls back to `PROCESSING` only if `output` itself has something manually placed; a no-op (nothing encoded) if neither holds. Kept as a standalone object, not inlined into the menu, so it's directly testable without a real menu/player.

Caught the same latent knbt bug `FilterMode`/`RoutingModule` did (see `docs/design/m2-sorting-routing.md`): a plain enum's default `encodeEnum` isn't supported by knbt's encoder at all, only silently "working" for whichever constant happens to be the field's declared default. `PatternKindSerializer` (encode/decode by name) fixes it the same way `FilterModeSerializer` does - confirmed by an actual crash on `PROCESSING` before the fix.

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
