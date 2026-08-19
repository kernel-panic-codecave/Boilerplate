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

`CraftingResolver.resolve(target, amount, stockOf, patternFor)` resolves into a **DAG**, not a tree — memoized per-resource within one resolution pass so, e.g., two branches both needing iron ingots share one sub-request rather than double-counting. `stockOf`/`patternFor` are plain functions, not tied to `WarehouseIndex`/`AssemblyTableBlockEntity` directly, so the algorithm itself is a pure, directly-testable unit; `CraftingRequest.resolve(level, from, target, amount)` is the real wiring, summing stock across every `WarehouseControllerBlockEntity` reachable from `from` (the same `RequestFulfillment.reachableWarehouses` a terminal withdrawal already searches) and taking the first pattern match found across every reachable `AssemblyTableBlockEntity`'s own patterns.

Turned out to need two passes, not one interleaved recursion - a shared resource's *total* demand (needed to decide how much actually has to come from stock vs. crafting) isn't known until every consumer of it has been discovered:

1. **Discover** - DFS from `target`, recording each resource touched in post-order (a resource's own pattern inputs are recorded before the resource itself); a resource reached while still in-progress further up the same branch fails fast as `Result.Cyclic`, rejecting an impossible/self-referential pattern chain cleanly instead of recursing forever.
2. **Demand** - walk that post-order **in reverse** (target first, deepest leaf ingredients last) accumulating each resource's total demand as its consumers are visited; by the time a resource itself is reached, every consumer that could ever add to its demand already has, since a consumer always sits earlier in the reversed order than what it consumes. A resource still short after stock is checked against its own pattern - `Result.Unresolvable` if it has neither.

The result (`CraftingResolver.Plan`) lists `steps` (one `CraftStep(pattern, runs)` per pattern actually needed, in bottom-up order - a leaf ingredient's own craft always precedes whatever consumes its output) and `stockPulls` (total pulled directly from stock, per resource, across the whole plan). Leaf ingredients still need to actually be pulled through the warehouse gantry job queue (M3) once execution (not just resolution) lands - see "Physical Assembly Table" below.

## Physical Assembly Table

[Decision #4](README.md#resolved-architectural-decisions): a real `AssemblyTableBlockEntity` that pipes feed and which visibly processes crafts over time, rather than resolving instantly once inputs are satisfied. Fits the mailroom/industrial-process theme, and gives M5's pressure mechanic something concrete to gate via the `PressureConsumer` hook (`basePressureCost`/`onPressureTick` scale how fast a craft completes — see [m5-pressure-power.md](m5-pressure-power.md)).

Every tick, `AssemblyTableBlockEntity.tick` picks the first of its own `patterns` whose `requiredInputs()` are all currently satisfied in `grid` and whose `output` has room for the result (`canRun`, re-checked every tick rather than cached — an ingredient pulled back out mid-run genuinely aborts progress, since `progressTicks` resets to `0` the moment nothing matches). Once a pattern matches, `progressTicks` accumulates `onPressureTick(NO_PRESSURE_LINE)` (a stand-in energy line until M5, so this is presently a flat `1.0`/tick) each tick; at `PROCESSING_TIME_TICKS` (100, ~5 seconds at 1.0x) it consumes the inputs and produces the outputs (`run`) and resets. Confirmed genuinely gated (not instant) via a gametest that reverted the tick-budget check and watched it fail.

`grid`/`output` are one shared slot pair for both authoring and running a pattern — the same cells `AssemblyTableMenu`'s "Encode" button reads from also feed `tick`'s matching, and pipes reach them through `ioStorage`, a small `CommonStorage<ItemResource>` wrapper that routes `insert` to `grid` and `extract` to `output` regardless of which slot index a call touches (`get`/`size` still expose both combined, for generic introspection), exposed via `exposeRackStorage` (the same helper the rack block types use — generic over any `CommonStorage`, not rack-specific).

## Terminal

Reuses `WarehouseTerminalBlock`'s menu with a "Craft" tab (`TabContainerPanel`):
- Searchable list with a craftable-amount preview (a `simulate = true` dry-run of the DAG).
- A request button.
- A job-status panel — status is a `@Sync`'d field on the terminal's block entity (reused sync infra, not a new packet).

Request submission itself is a small client→server `CraftingRequestPacket(resource, amount)` — an *action*, not state, so it's the one place in this milestone that needs a bespoke packet rather than `@Sync`/`observeProperty`.

## Deferred to playtesting / not blocking

- Exact pattern-editing UX (drag-from-search-results vs. requiring physical items in hand for encoding).
