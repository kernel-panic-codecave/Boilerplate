# M2 — Sorting & Routing

See [README.md](README.md) for shared conventions and [m1-pipe-network.md](m1-pipe-network.md) for the pipe network/BFS routing this extends — M2 does not add a new network type, it extends the pipe.

## Module data

M2's filter/priority/color config is one of `HookBlockEntity`'s per-face hooks (see [m1-pipe-network.md](m1-pipe-network.md#hooks-attachments-not-separate-blocks)) — a `SortingHookType` hook, attached to a specific face by right-clicking a `HookItem` against it, rather than a whole-pipe, one-time-unlock upgrade. Each hook type owns its own self-contained `HookHolderState` subclass, an `NBTHolder` in its own right (see [m1-pipe-network.md](m1-pipe-network.md#hooks-attachments-not-separate-blocks) for how `HookBlockEntity.hooks: NestedNBTHolderMap` holds heterogeneous entries keyed by `Direction.name`):

```kotlin
enum class FilterMode { WHITELIST, BLACKLIST }

@Serializable
data class RoutingModule(
    val mode: FilterMode = FilterMode.WHITELIST,
    val priority: Int = 0,
    val color: DyeColor? = null,
)

abstract class HookHolderState(defaultType: ResourceLocation) : NBTHolder by NBTHolder.create() {
    val type: ResourceLocation by field(ResourceLocationSerializer) { defaultType }
}

class SortingHookState : HookHolderState(SortingHookType.ID) {
    var routing: RoutingModule by field(RoutingModule.serializer()) { RoutingModule() }
    val filter by itemField(9)
}

class ExtractionHookState : HookHolderState(ExtractionHookType.ID) {
    var ticksSinceExtraction: Int by intField()
    // color: DyeColor? — see ColorSlot in ExtractionHookState.kt for why this isn't a bare
    // nullable field (a `field<T?>` encodes rootless, and knbt can't represent a bare `null`
    // there — only inside a `@Serializable` type's own structured encoding).
}
```

`filter` (one 9-slot grid) lives directly on `SortingHookState` rather than as six always-allocated per-face fields on `HookBlockEntity` — only a face actually carrying a sorting hook has one at all, matching the "self-contained per hook" model. An `ExtractionHookType` hook has its own independent `color: DyeColor?` (stamped onto whatever it pulls) rather than sharing `SortingHookState.routing` — the two hook kinds no longer share one flat state shape, so each keeps only the fields it actually uses.

## GUI

`SortingPipeMenu(id, inventory, tile, direction) : ComposeBlockContainerMenu<HookBlockEntity, SortingPipeMenu>` — `direction` identifies which face's sorting hook is being edited (round-tripped from `HookBlockEntity.pendingMenuFace`, set right before `MenuRegistry.openExtendedMenu` and written by `saveExtraData` for the client to reconstruct the same menu). `handler("filter", tile.filterFor(direction))` bound to a `Slots("filter", 3, 3)` composable, a `RadioGroup<FilterMode>` for whitelist/blacklist, a `Slider` (0..10, normalized internally since Archie's `Slider` always operates on a `Float` in `[0,1]`) for priority. Color is a `RadioGroup<DyeColor?>` (17 options: `null` "Any" plus all 16 `DyeColor`s), **not** Archie's continuous `ColorPicker` — the design originally called for `ColorPicker`, but routing compares color by exact equality against a discrete `DyeColor?`, and a continuous `HsvColor` picker doesn't map onto that cleanly without a lossy nearest-match step.

`SortingPipeMenu.currentRouting()` reads `(tile.hooks[direction.name] as? SortingHookState)?.routing` once when the screen opens, into local Compose state (`remember { mutableStateOf(...) }`) rather than observing it live — a `NestedNBTHolderMap` entry isn't wired into `BlockEntityStateManager` for `observeProperty` the way a top-level `@Sync` field is (see [m1-pipe-network.md](m1-pipe-network.md#hooks-attachments-not-separate-blocks)). Edits update that local state immediately (optimistic UI) and push a dedicated C2S `UpdateSortingRoutingPacket(pos, direction, routing)` to persist them server-side, which looks up the same hook by `pos`/`direction`, writes `routing`, calls `hooks.touch()`, and resyncs via `level.sendBlockUpdated`.

Block interaction: `HookBlock.clickBlockWithItem` attaches a hook (consumes one `HookItem`, `hooks.getOrPut(hitFace.name) { hookType.createState() }`) when right-clicked against a face that doesn't already carry one — or, against a plain `PipeBlock`, promotes it to a `HookBlock` first (see [m1-pipe-network.md](m1-pipe-network.md#hooks-attachments-not-separate-blocks)); `HookBlock.useWithoutItem` opens `SortingPipeMenu` for that face if its hook's `PipeHookType.hasMenu` is true, or - while sneaking - removes the hook.

## Color-coded routing (Logistics-Pipes homage)

Real Minecraft items can't cleanly carry an arbitrary "color" tag, so the color rides on the **in-flight envelope** instead: `TravelingItem` (from M1) gained a `val color: DyeColor? = null` field, carried through every hop (including pipe-to-pipe hand-offs — a real bug caught during implementation: the hop code originally dropped `color` by not passing it through `TravelingItem.copy`-equivalent reconstruction). Set once at spawn by the extractor that initiated the trip, from its own hook's `ExtractionHookState.color`. Sorting pipes branch on `travelingItem.color == null || sortingHook.routing.color == travelingItem.color`. Plain (non-sorting) endpoints and pipes accept any color.

## Filtering & routing decision

Filter grid entries are matched by item identity (`ItemResource.isOf(resource.item)`, ignoring data components) rather than CSL's `TransferUtil.byIngredient`/`byItemTag` — the design originally proposed those, but this is simpler and sufficient for "filter by item" without needing to verify their exact signatures; revisit if component-aware filtering is wanted later. `PipeRouter.search` was rewritten from M1's shape to:

1. BFS the whole reachable space (not stopping at first hit, unlike M1).
2. For each candidate reached through a pipe with a sorting module applied: reject if the module's `color` is set and doesn't match the item's, or if the module's filter/mode rejects the resource. A candidate reached through a plain pipe always accepts (color/filter blind), at baseline priority 0.
3. Among everything still valid, keep the one with the highest `RoutingModule.priority` (ties broken by fewer hops).

Routing cache key extended to `(networkId, network.version, resource, color, exclude)` (`exclude` already added in M1's own bugfix pass — see [m1-pipe-network.md](m1-pipe-network.md)).

## Deferred to playtesting / not blocking

- Priority as a 0–10 slider vs. discrete tiers (Highest/High/Normal/Low) — UX polish, not architecture.
- Filter matching by item identity only (not data components) — revisit if a use case needs it.
