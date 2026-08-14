# M2 — Sorting & Routing

See [README.md](README.md) for shared conventions and [m1-pipe-network.md](m1-pipe-network.md) for the pipe network/BFS routing this extends — M2 does not add a new network type, it extends the pipe.

## Module data

M2's filter/priority/color config is one of `HookBlockEntity`'s per-face hooks (see [m1-pipe-network.md](m1-pipe-network.md#hooks-attachments-not-separate-blocks)) — a `SortingHookType` hook, attached to a specific face by right-clicking a `HookItem` against it, rather than a whole-pipe, one-time-unlock upgrade:

```kotlin
enum class FilterMode { WHITELIST, BLACKLIST }

@Serializable
data class RoutingModule(
    val mode: FilterMode = FilterMode.WHITELIST,
    val priority: Int = 0,
    val color: DyeColor? = null,
)

@Serializable
data class HookState(
    val type: SResourceLocation,      // full HookTypeRegistry id, e.g. SortingHookType.ID/ExtractionHookType.ID -
                                       // a real ResourceLocation, not assumed namespaced under Tubular Storage,
                                       // so an addon mod's own PipeHookType round-trips too
    val routing: RoutingModule = RoutingModule(),
    val ticksSinceExtraction: Int = 0,
)   // routing and ticksSinceExtraction are shared storage interpreted differently per hook type -
    // a sorting hook reads all of routing against the face's filter grid; an extraction hook only
    // reads routing.color and owns its own ticksSinceExtraction cooldown

// on HookBlockEntity:
@Sync
val hooks by mapField(HookState.serializer()) { emptyMap() }   // keyed by Direction.name

val filterNorth by itemField(9)   // ...and filterSouth/East/West/Up/Down: one 3x3 grid per face,
                                   // always allocated (ArchieItemStorage needs a stable identity
                                   // for slot UI wiring), only meaningful for a face actually
                                   // carrying a sorting hook
```

An `ExtractionHookType` hook reads the *same* per-face `HookState.routing.color` (via `HookBlockEntity.routingFor(direction)`, ignoring `mode`/`priority`/the filter grid) to tag what it pulls with a color — it does not filter *what* gets extracted, only stamps the color onto the resulting `TravelingItem`. `routing` lives inside `HookState`, folded into the single `@Sync`-annotated `hooks` map, rather than a separate per-face field: an earlier pass split it out into its own field specifically to dodge two Archie `@Sync` bugs found while building this - `NBTHolder.mapField`/`listField` registered the wrong (element, not collection) serializer for `@Sync`, and `BlockEntityStateContainer`'s reflective property scan used module-less, generics-erased `KClass.serializerOrNull()`, which can't resolve a `@Contextual`-annotated field at all. Both are now fixed upstream in Archie, so `routing` folded back into `HookState` once they landed - see [m1-pipe-network.md](m1-pipe-network.md#deferred-to-playtesting--not-blocking).

## GUI

`SortingPipeMenu(id, inventory, tile, direction) : ComposeBlockContainerMenu<HookBlockEntity, SortingPipeMenu>` — `direction` identifies which face's sorting hook is being edited (round-tripped from `HookBlockEntity.pendingMenuFace`, set right before `MenuRegistry.openExtendedMenu` and written by `saveExtraData` for the client to reconstruct the same menu). `handler("filter", tile.filterFor(direction))` bound to a `Slots("filter", 3, 3)` composable, a `RadioGroup<FilterMode>` for whitelist/blacklist, a `Slider` (0..10, normalized internally since Archie's `Slider` always operates on a `Float` in `[0,1]`) for priority. Color is a `RadioGroup<DyeColor?>` (17 options: `null` "Any" plus all 16 `DyeColor`s), **not** Archie's continuous `ColorPicker` — the design originally called for `ColorPicker`, but routing compares color by exact equality against a discrete `DyeColor?`, and a continuous `HsvColor` picker doesn't map onto that cleanly without a lossy nearest-match step. Backed by the `@Sync val hooks: Map<String, HookState>` field, read via `observeProperty("hooks", emptyMap<String, HookState>())` and indexed/updated by the menu's `direction` (`hooks[faceKey]`/`hooks[faceKey] = state.copy(routing = next)`) — **no new packet type needed**, writes push automatically through `BlockEntityStateManager` per the shared GUI convention.

Block interaction: `HookBlock.useItemOn` attaches a hook (consumes one `HookItem`, sets `hooks[hitFace] = HookState(type = ...)`) when right-clicked against a face that doesn't already carry one - or, against a plain `PipeBlock`, `PipeBlock.useItemOn` promotes it to a `HookBlock` first (see [m1-pipe-network.md](m1-pipe-network.md#hooks-attachments-not-separate-blocks)); `HookBlock.useWithoutItem` opens `SortingPipeMenu` for that face if its hook's `PipeHookType.hasMenu` is true, or - while sneaking - removes the hook.

## Color-coded routing (Logistics-Pipes homage)

Real Minecraft items can't cleanly carry an arbitrary "color" tag, so the color rides on the **in-flight envelope** instead: `TravelingItem` (from M1) gained a `val color: DyeColor? = null` field, carried through every hop (including pipe-to-pipe hand-offs — a real bug caught during implementation: the hop code originally dropped `color` by not passing it through `TravelingItem.copy`-equivalent reconstruction). Set once at spawn by the extractor that initiated the trip, from its own `routing.color` if it has a sorting module. Sorting pipes branch on `travelingItem.color == null || sortingPipe.routing.color == travelingItem.color`. Plain (non-sorting) endpoints and pipes accept any color.

## Filtering & routing decision

Filter grid entries are matched by item identity (`ItemResource.isOf(resource.item)`, ignoring data components) rather than CSL's `TransferUtil.byIngredient`/`byItemTag` — the design originally proposed those, but this is simpler and sufficient for "filter by item" without needing to verify their exact signatures; revisit if component-aware filtering is wanted later. `PipeRouter.search` was rewritten from M1's shape to:

1. BFS the whole reachable space (not stopping at first hit, unlike M1).
2. For each candidate reached through a pipe with a sorting module applied: reject if the module's `color` is set and doesn't match the item's, or if the module's filter/mode rejects the resource. A candidate reached through a plain pipe always accepts (color/filter blind), at baseline priority 0.
3. Among everything still valid, keep the one with the highest `RoutingModule.priority` (ties broken by fewer hops).

Routing cache key extended to `(networkId, network.version, resource, color, exclude)` (`exclude` already added in M1's own bugfix pass — see [m1-pipe-network.md](m1-pipe-network.md)).

## Deferred to playtesting / not blocking

- Priority as a 0–10 slider vs. discrete tiers (Highest/High/Normal/Low) — UX polish, not architecture.
- Filter matching by item identity only (not data components) — revisit if a use case needs it.
