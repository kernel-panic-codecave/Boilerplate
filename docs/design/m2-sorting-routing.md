# M2 — Sorting & Routing

See [README.md](README.md) for shared conventions and [m1-pipe-network.md](m1-pipe-network.md) for the pipe network/BFS routing this extends — M2 does not add a new network type, it extends the pipe.

## Module data

M2's filter/priority/color config is one of `PipeBlockEntity`'s per-face hooks (see [m1-pipe-network.md](m1-pipe-network.md#hooks-ae2-import-bus-style-attachments-not-separate-blocks)) — a `SortingHookType` hook, attached to a specific face by right-clicking a `HookItem` against it, rather than a whole-pipe, one-time-unlock upgrade:

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
    val type: SResourceLocation,  // full HookTypeRegistry id, e.g. SortingHookType.ID/ExtractionHookType.ID -
                                   // a real ResourceLocation, not assumed namespaced under Tubular Storage,
                                   // so an addon mod's own PipeHookType round-trips too
    val ticksSinceExtraction: Int = 0,
)

@Serializable
data class FaceRouting(
    val north: RoutingModule = RoutingModule(),
    val south: RoutingModule = RoutingModule(),
    val east: RoutingModule = RoutingModule(),
    val west: RoutingModule = RoutingModule(),
    val up: RoutingModule = RoutingModule(),
    val down: RoutingModule = RoutingModule(),
)   // get(direction)/with(direction, module) for lookup/update by Direction

// on PipeBlockEntity:
val hooks by mapField(HookState.serializer()) { emptyMap() }   // keyed by Direction.name, not @Sync - see below

@Sync
var routing by field(FaceRouting.serializer()) { FaceRouting() }   // routingFor/setRoutingFor(direction) wrap [direction]/with(...)

val filterNorth by itemField(9)   // ...and filterSouth/East/West/Up/Down: one 3x3 grid per face,
                                   // always allocated (ArchieItemStorage needs a stable identity
                                   // for slot UI wiring), only meaningful for a face actually
                                   // carrying a sorting hook
```

An `ExtractionHookType` hook reads the *same* per-face `RoutingModule.color` (via `PipeBlockEntity.routingFor(direction)`, ignoring `mode`/`priority`/the filter grid) to tag what it pulls with a color — it does not filter *what* gets extracted, only stamps the color onto the resulting `TravelingItem`. `routing` is **not** folded into `HookState` inside the `hooks` map, even though conceptually it's per-face hook config like everything else: building this exposed two Archie `@Sync` bugs - `NBTHolder.mapField`/`listField` registered the wrong (element, not collection) serializer, and `BlockEntityStateContainer`'s reflective property scan keyed by the raw Kotlin property name while `NBTHolder`'s manual registration/dirty-tracking keyed by `property.name.toSnakeCase()`, silently desyncing any multi-word `@Sync` scalar field too (`routingNorth` → `routing_north`). Both are now fixed upstream in Archie, but `FaceRouting` (a single, single-word `routing` field holding one `RoutingModule` per face, exactly like M1's original design just with a richer value type) is left as-is rather than folding back into a `@Sync`-annotated `hooks` map - a mechanical follow-up cleanup, not required for correctness.

## GUI

`SortingPipeMenu(id, inventory, tile, direction) : ComposeBlockContainerMenu<PipeBlockEntity, SortingPipeMenu>` — `direction` identifies which face's sorting hook is being edited (round-tripped from `PipeBlockEntity.pendingMenuFace`, set right before `MenuRegistry.openExtendedMenu` and written by `saveExtraData` for the client to reconstruct the same menu). `handler("filter", tile.filterFor(direction))` bound to a `Slots("filter", 3, 3)` composable, a `RadioGroup<FilterMode>` for whitelist/blacklist, a `Slider` (0..10, normalized internally since Archie's `Slider` always operates on a `Float` in `[0,1]`) for priority. Color is a `RadioGroup<DyeColor?>` (17 options: `null` "Any" plus all 16 `DyeColor`s), **not** Archie's continuous `ColorPicker` — the design originally called for `ColorPicker`, but routing compares color by exact equality against a discrete `DyeColor?`, and a continuous `HsvColor` picker doesn't map onto that cleanly without a lossy nearest-match step. Backed by the single `@Sync var routing: FaceRouting` field, read via `observeProperty("routing", FaceRouting())` and indexed/updated by the menu's `direction` (`faceRouting[direction]`/`faceRouting.with(direction, next)`) — **no new packet type needed**, writes push automatically through `BlockEntityStateManager` per the shared GUI convention.

Block interaction (on `PipeBlock`): `useItemOn` attaches a hook (consumes one `HookItem`, sets `hooks[hitFace] = HookState(type = ...)`) when right-clicked against a face that doesn't already carry one; `useWithoutItem` opens `SortingPipeMenu` for that face if its hook's `PipeHookType.hasMenu` is true, or - while sneaking - removes the hook.

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
