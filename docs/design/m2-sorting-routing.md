# M2 — Sorting & Routing

See [README.md](README.md) for shared conventions and [m1-pipe-network.md](m1-pipe-network.md) for the pipe network/BFS routing this extends — M2 does not add a new network type, it extends the pipe.

## Module data

Stored directly on `PipeBlockEntity` once the `SortingModuleItem` has been used on it ([decision #1](README.md#resolved-architectural-decisions): a one-time unlock, not a persistent physical dependency):

```kotlin
enum class FilterMode { WHITELIST, BLACKLIST }

@Serializable
data class RoutingModule(
    val mode: FilterMode = FilterMode.WHITELIST,
    val priority: Int = 0,
    val color: DyeColor? = null,
)

// on PipeBlockEntity:
var hasSortingModule by booleanField()   // gates the GUI and routing behavior below

@Sync
var routing by field(RoutingModule.serializer()) { RoutingModule() }

val filter by itemField(9)   // 3x3 filter grid, ArchieItemStorage
```

`ExtractorPipeBlockEntity` inherits these fields (it's a `PipeBlockEntity` subclass), so the same module item and GUI also let an extractor tag what it pulls with a color — it does not filter *what* gets extracted, only stamps the color onto the resulting `TravelingItem`.

## GUI

`SortingPipeMenu : ComposeBlockContainerMenu<PipeBlockEntity, SortingPipeMenu>` — `handler("filter", tile.filter)` bound to a `Slots("filter", 3, 3)` composable, a `RadioGroup<FilterMode>` for whitelist/blacklist, a `Slider` (0..10, normalized internally since Archie's `Slider` always operates on a `Float` in `[0,1]`) for priority. Color is a `RadioGroup<DyeColor?>` (17 options: `null` "Any" plus all 16 `DyeColor`s), **not** Archie's continuous `ColorPicker` — the design originally called for `ColorPicker`, but routing compares color by exact equality against a discrete `DyeColor?`, and a continuous `HsvColor` picker doesn't map onto that cleanly without a lossy nearest-match step. All backed by a single `@Sync var routing: RoutingModule` field read via `observeProperty("routing", RoutingModule())` — **no new packet type needed**, writes push automatically through `BlockEntityStateManager` per the shared GUI convention; kotlinx CBOR-encodes the whole data class transparently, no per-field wiring required.

Block interaction (on `PipeBlock`, inherited by `ExtractorPipeBlock`): `useItemOn` applies the module (consumes one `SortingModuleItem`, sets `hasSortingModule = true`, one-way — no removal) when right-clicked with it; `useWithoutItem` opens `SortingPipeMenu` only if `hasSortingModule` is already set.

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
