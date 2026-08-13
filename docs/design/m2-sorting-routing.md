# M2 — Sorting & Routing

See [README.md](README.md) for shared conventions and [m1-pipe-network.md](m1-pipe-network.md) for the pipe network/BFS routing this extends — M2 does not add a new network type, it extends the pipe.

## Module data

Stored directly on `PipeBlockEntity` once a "Sorting" module item has been applied ([decision #1](README.md#resolved-architectural-decisions): a one-time unlock, not a persistent physical dependency — simpler than tracking an itemstack-in-pipe forever):

```kotlin
@Serializable
data class RoutingModule(
    val mode: FilterMode = FilterMode.WHITELIST,       // WHITELIST | BLACKLIST
    val priority: Int = 0,
    val color: DyeColor? = null,
)

// on PipeBlockEntity, once the sorting module is applied:
var routing by nbt.field(RoutingModule.serializer()) { RoutingModule() }
val filter by nbt.itemField(9)   // 3x3 filter grid, ArchieItemStorage
```

## GUI

`SortingPipeMenu : ComposeBlockContainerMenu<PipeBlockEntity, SortingPipeMenu>` — `handler("filter", tile.filter)` bound to a `Slots("filter", 3, 3)` composable, a `RadioGroup` for whitelist/blacklist, an `intSlider` for priority, a `ColorPicker` for the consignment color. All backed by `@Sync` fields read via `observeProperty` — **no new packet type needed**, writes push automatically through `BlockEntityStateManager` per the shared GUI convention.

## Color-coded routing (Logistics-Pipes homage)

Real Minecraft items can't cleanly carry an arbitrary "color" tag, so the color rides on the **in-flight envelope** instead: `TravelingItem` (from M1) gains `val color: DyeColor? = null`, set by whichever extractor/crafting-request initiated the trip (itself configured with the same `ColorPicker` pattern). Sorting pipes branch on `travelingItem.color == routing.color || routing.color == null`. Plain (non-sorting) endpoints accept any color. This reproduces "colored consignments routed to like-colored endpoints" without a separate subscriber registry.

## Filtering & routing decision

Filter grid entries become `Predicate<ItemResource>` via CSL's `TransferUtil.byIngredient`/`byItemTag`, combined with whitelist/blacklist mode. The routing decision at a junction becomes:

1. Enumerate reachable sorting-tagged endpoints via M1's BFS — but now *not* stopping at the first hit.
2. Reject endpoints whose filter/color don't match.
3. Pick by `(priority desc, hop-count asc)`.

Routing cache key becomes `(networkId, network.version, resource, color)`.

## Deferred to playtesting / not blocking

- Priority as a raw `intSlider` vs. discrete tiers (Highest/High/Normal/Low) — UX polish, not architecture.
