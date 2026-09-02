# M2 — Sorting & Routing

See [README.md](README.md) for shared conventions and [m1-pipe-network.md](m1-pipe-network.md) for the pipe network/BFS routing this extends — M2 does not add a new network type, it extends the pipe.

## Hook taxonomy

M2's `FilterHookType`/`ExtractionHookType` and M3's `RequesterHookType`/`ProviderHookType` (see [m3-warehouse-storage.md](m3-warehouse-storage.md#request-based-routing-logistics-pipes-requestprovider-pipes)) fall into a clean 2x2 once all four exist - source vs. sink, crossed with self-initiating ("active") vs. only-consulted-when-something-else-acts ("passive"):

|                    | **Active** (initiates every tick/interval) | **Passive** (only acts when consulted) |
|--------------------|---------------------------------------------|------------------------------------------|
| **Provider** (source) | `ExtractionHookType` - pushes out on its own interval | `ProviderHookType` - available when a request finds it |
| **Requester** (sink)  | `RequesterHookType` - issues standing-order pulls | `FilterHookType` - a push-model candidate destination, ranked by filter/priority |

Not a design decision that shaped any one of the four - it fell out after M3's request-based hooks landed - but worth naming since it's a useful lens for where a *new* hook kind belongs: does it originate transfers on its own, or only respond to one already in flight; and is it a source or a sink for the resource in question.

`InterfaceHookType` (see "Hook-to-hook facing as a subnet boundary" below) doesn't fit this grid at all - it's neither a pure source nor a pure sink in the push/pull sense. Across a boundary it is deliberately a passive seam (a physical buffer other hooks on either side read from or write to), while on its own side it both self-requisitions from the network (sink) and drains its own excess back out (source), always toward its own configured target rather than by invitation.

## Module data

M2's filter/priority/color config is one of `HookBlockEntity`'s per-face hooks (see [m1-pipe-network.md](m1-pipe-network.md#hooks-attachments-not-separate-blocks)) — a `FilterHookType` hook, attached to a specific face by right-clicking a `HookItem` against it, rather than a whole-pipe, one-time-unlock upgrade. Each hook type owns its own self-contained `HookHolderState` subclass, an `NBTHolder` in its own right (see [m1-pipe-network.md](m1-pipe-network.md#hooks-attachments-not-separate-blocks) for how `HookBlockEntity.hooks: NestedNBTHolderMap` holds heterogeneous entries keyed by `Direction.name`):

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
    val filter: ArchieItemStorage by itemField(1, filter = { it.item is FilterCardItem })
}

class ExtractionHookState : HookHolderState(ExtractionHookType.ID) {
    var ticksSinceExtraction: Int by intField()
    // color: DyeColor? — see ColorSlot in ExtractionHookState.kt for why this isn't a bare
    // nullable field (a `field<T?>` encodes rootless, and knbt can't represent a bare `null`
    // there — only inside a `@Serializable` type's own structured encoding).
}
```

`filter` (one real filter-card slot — see [Filtering & routing decision](#filtering--routing-decision) for why it's a single card rather than the 3x3 grid it began as) lives directly on `SortingHookState` rather than as six always-allocated per-face fields on `HookBlockEntity` — only a face actually carrying a sorting hook has one at all, matching the "self-contained per hook" model. An `ExtractionHookType` hook has its own independent `color: DyeColor?` (stamped onto whatever it pulls) rather than sharing `SortingHookState.routing` — the two hook kinds no longer share one flat state shape, so each keeps only the fields it actually uses.

## GUI

`SortingPipeMenu(id, inventory, tile, direction) : ComposeBlockContainerMenu<HookBlockEntity, SortingPipeMenu>` — `direction` identifies which face's sorting hook is being edited (round-tripped from `HookBlockEntity.pendingMenuFace`, set right before `MenuRegistry.openExtendedMenu` and written by `saveExtraData` for the client to reconstruct the same menu). `handler("filter", tile.filterFor(direction)) { it.item is FilterCardItem }` bound to a `Slots("filter")` composable, a `RadioGroup<FilterMode>` for whitelist/blacklist, a `Slider` (0..10, normalized internally since Archie's `Slider` always operates on a `Float` in `[0,1]`) for priority. Color is a `RadioGroup<DyeColor?>` (17 options: `null` "Any" plus all 16 `DyeColor`s), **not** Archie's continuous `ColorPicker` — the design originally called for `ColorPicker`, but routing compares color by exact equality against a discrete `DyeColor?`, and a continuous `HsvColor` picker doesn't map onto that cleanly without a lossy nearest-match step.

`SortingPipeMenu.currentRouting()` reads `(tile.hooks[direction.name] as? SortingHookState)?.routing` once when the screen opens, into local Compose state (`remember { mutableStateOf(...) }`) rather than observing it live — a `NestedNBTHolderMap` entry isn't wired into `BlockEntityStateManager` for `observeProperty` the way a top-level `@Sync` field is (see [m1-pipe-network.md](m1-pipe-network.md#hooks-attachments-not-separate-blocks)). Edits update that local state immediately (optimistic UI) and push a dedicated C2S `UpdateSortingRoutingPacket(pos, direction, routing)` to persist them server-side, which looks up the same hook by `pos`/`direction`, writes `routing`, calls `hooks.touch()`, and resyncs via `level.sendBlockUpdated`.

Block interaction: `HookBlock.clickBlockWithItem` attaches a hook (consumes one `HookItem`, `hooks.getOrPut(hitFace.name) { hookType.createState() }`) when right-clicked against a face that doesn't already carry one — or, against a plain `PipeBlock`, promotes it to a `HookBlock` first (see [m1-pipe-network.md](m1-pipe-network.md#hooks-attachments-not-separate-blocks)); `HookBlock.useWithoutItem` opens `SortingHookMenu` for that face if its hook's `PipeHookType.hasMenu` is true, or - while sneaking - removes the hook.

## Color-coded routing (Logistics-Pipes homage)

Real Minecraft items can't cleanly carry an arbitrary "color" tag, so the color rides on the **in-flight envelope** instead: `TravelingItem` (from M1) gained a `val color: DyeColor? = null` field, carried through every hop (including pipe-to-pipe hand-offs — a real bug caught during implementation: the hop code originally dropped `color` by not passing it through `TravelingItem.copy`-equivalent reconstruction). Set once at spawn by the extractor that initiated the trip, from its own hook's `ExtractionHookState.color`. Sorting pipes branch on `travelingItem.color == null || sortingHook.routing.color == travelingItem.color`. Plain (non-sorting) endpoints and pipes accept any color.

## Filtering & routing decision

**`SortingHookState.filter` is a single real slot holding one filter card**, not the 3x3 ghost grid it started as. It's an `ArchieItemStorage` of size 1 restricted to `FilterCardItem`, registered by `SortingHookMenu` as an ordinary vanilla `Slot` — exactly the shape `RackBlockEntity.filter` already used, adopted here so both configure the same way. A card placed in it is genuinely consumed from the player's inventory and can be taken back out, unlike a ghost entry, which was only ever a reference.

One slot loses no expressiveness over the old nine: filtering on plain item identity is what an `ItemConditionType` card's own ghost grid is for, and several conditions still combine through a `CombinedConditionType` card. It does mean the hook-side ghost plumbing is gone entirely — `FilterCardTarget.HookFilterSlot` and `HookGhostSlotItemAccess` were deleted, and a hook's card is now configured while held (a `PlayerSlot` target) before being placed, the same way a rack's is. `SetGhostSlotPacket`/`GhostSlotGrid` remain for the places that genuinely still are ghosts: a card's own `itemMatches`/`children`, and the Pattern Terminal's authoring grid.

An **empty** filter slot deliberately keeps the old grid's semantics — accepts nothing under whitelist, everything under blacklist. That differs from `RackBlockEntity.acceptsByFilter`, where an unconfigured rack takes anything, and the difference is intentional: a sorting hook's empty whitelist is a meaningful "deny everything" configuration, whereas an unfiltered rack is just a rack nobody has got to yet.

Card conditions themselves are matched by item identity (`ItemResource.isOf(resource.item)`, ignoring data components unless `ItemConditionState.matchComponents` is set) rather than CSL's `TransferUtil.byIngredient`/`byItemTag` — the design originally proposed those, but this is simpler and sufficient without needing to verify their exact signatures. `PipeRouter.search` was rewritten from M1's shape to:

1. BFS the whole reachable space (not stopping at first hit, unlike M1).
2. For each candidate reached through a pipe with a sorting module applied: reject if both the module's `color` and the item's are set and they differ (a colorless item — `color == null`, e.g. a machine push routed through an interface's pass-through face — is color-agnostic and never rejected by a colored module), or if the module's filter/mode rejects the resource. A candidate reached through a plain pipe always accepts (color/filter blind), at baseline priority 0.
3. Among everything still valid, keep the one with the highest `RoutingModule.priority` (ties broken by fewer hops).

Routing cache key extended to `(networkId, network.version, resource, color, exclude)` (`exclude` already added in M1's own bugfix pass — see [m1-pipe-network.md](m1-pipe-network.md)).

## Default route (Logistics Pipes homage)

`RoutingModule.DEFAULT_ROUTE_PRIORITY` (`-1`) is a reserved sentinel below the normal `0`..`10` priority range - Logistics Pipes' Default Route, a catch-all sink that only wins when nothing else on the network accepts an item. Needed **zero** changes to `PipeRouter.search`'s candidate evaluation: `module.priority` was already a plain, sign-agnostic `Int` comparison, so a sorting hook set to `-1` (typically paired with blacklist mode and an empty filter, to accept anything) naturally loses to every other candidate and only gets chosen when it's the sole option. Any sorting-hook-guarded destination can claim it this way - including a bound warehouse's inbound buffer (see [m3-warehouse-storage.md](m3-warehouse-storage.md)), with no warehouse-specific routing code needed either.

Only one sorting hook per network may hold the sentinel at a time: `UpdateSortingRoutingPacket.handleOnServer`, when applying an edit that sets it, walks the network's members and resets any *other* hook already holding it back to `0`. Enforced at assignment time only, not on network-topology changes - see "Deferred to playtesting" below for the merge edge case this leaves open.

Caught a real, previously-latent bug along the way: `FilterMode` (a plain Kotlin enum) relied on kotlinx.serialization's default `encodeEnum`, which knbt's `AbstractNbtEncoder` never overrides - it silently "worked" only because `FilterMode.WHITELIST` is the field's declared default, which kotlinx.serialization skips encoding entirely; `BLACKLIST` (or any explicit non-default enum value nested this way) crashed the instant something actually tried to persist it. Fixed with `FilterModeSerializer`, encoding by name via `encodeString`/`decodeString` - the same workaround `DirectionSerializer`/`DyeColorSerializer` already use for the same underlying knbt gap.

## Hook-to-hook facing as a subnet boundary

Ordinarily `PipeNetworkManager` merges every reachable, matching pipe into one flat graph regardless of what's attached to it — a hook is just a per-face attachment *within* that one network, uniformly visible to `PipeRouter.search` from anywhere else in it. `InterfaceHookType` is the one exception: a hook facing directly into an `InterfaceHookType` hook (or vice versa) keeps the two sides logically separate networks instead of merging, bridged only through the interface's own small physical buffer - the same shape as AE2's Storage Bus attached to an Interface, a narrow, deliberate seam rather than the two networks becoming one.

**`InterfaceHookType`/`InterfaceHookState`**: a stocking reservoir with a pass-through face. The state holds two rows of nine - `ghosts`, the interface's stocking targets (one target stack per column, capped naturally at its stack size, never drained or refilled by the network) over real held `stock`. The exposed surface is *not* the stock row itself: `exposedItemStorage` returns an `InterfacePassThroughStorage` whose reads/extractions hit `stock` but whose `insert` routes the item straight into the network via `PipeRouter.findRoute` (or returns `0` when nothing accepts) - the same holds for per-slot writes off `get(i)`, which is how CSL's NeoForge `IItemHandler` bridge (most cross-mod machines) actually drives items in - so a generic push - from the boundary partner across the seam, or from a machine/hopper physically touching the face - passes straight through the interface into its far side rather than being staged. The route excludes the machine's own position (found via the hook face), so a hopper on the face can't get its items bounced straight back into itself. A pipe *delivering* an item for scatter isn't excluded the same way: the route may travel back through it to reach a far destination. Interface-initiated work is targeted instead: `requisitionStock` self-requests each `ghosts` column's shortfall from the network (delivered into `stock` by `PipeBlockEntity.tick`'s deposit-time special-case for an explicitly targeted face), and `drainExcess` pushes only what's above a stock column's own target back out - so `stock` is a reservoir tracking its ghost row, not a transit buffer. Has its own `hasMenu`/`InterfaceHookMenu` (the `ghost` row over the `stock` row, chest-simple) for direct player config and interaction.

**`SubnetBoundary.isBoundaryEdge(level, pos, direction)`** is the one shared check: true whenever either `pos`'s own `direction` face or the neighbor's facing-back face carries an `InterfaceHookType` hook. Every place that otherwise treats hook-carrying pipe adjacency as free-flowing topology has to stop right at a boundary edge instead of walking through it - turned out to be **four** separate places, not one, each with its own BFS/hop logic that needed the same carve-out:

- `PipeNetworkManager.ensureRegistered` - skips the merge across a boundary edge, so the two sides get (and keep) distinct network ids/versions. Confirmed necessary the hard way: without it, `UpdateSortingRoutingPacket`'s "only one default-route sentinel per network" walk would incorrectly treat both sides as one network.
- `PipeRouter.step` (push-routing BFS) - a boundary edge is evaluated as a normal candidate (against whatever `current`'s own attached hook is) instead of enqueued for further walking, so the far network's topology beyond the interface itself never becomes visible to this search.
- `PipeRouter.findRouteTo` (targeted delivery routing, used by `RequestFulfillment`'s own delivery leg and `RequesterHookType`'s standing orders) - same carve-out, but only for *continuing through*; reaching the boundary-crossed position as the literal destination (`to`) still succeeds, since a request delivering *into* an interface is legitimate. Caught a real, easy-to-miss bug here: without this fix, a delivery route that happened to pass geometrically near a boundary could find a shorter path straight through the far network instead of staying within its own - the extracted stock would travel out and silently land right back in the same interface it came from, net effect zero, no error either.
- `PipeBlockEntity.tick`'s own item-hop delivery logic - a *third*, independent "is this a pipe or a delivery target" check, easy to miss because it's not shaped like a BFS at all. A route ending at a boundary always terminates its own hop list right at the far hook's position, which is still, ordinarily, "a pipe" as far as this check is concerned; without the same carve-out, a delivered item kept trying to hop into a further pipe segment that was never actually part of the route and got jammed (dropped as a real item entity) instead of landing in the interface's stock.
- `RequestFulfillment.reachablePipes` (the terminal/standing-order search) - stops at a boundary edge too, so a resource sitting *deeper* in the far network (reachable from the interface via that network's own ordinary topology, not through the boundary itself) stays invisible to an outer-network request. Only what the interface itself exposes across the seam - its own target-delivered `stock`, or its pass-through surface to a routed push - is visible to the far side.

Since M5, `PressureNetworkBoundary` (see `m5-pressure-power.md`) folds this same edge in as a *pressure* boundary too, not just an item one - the two sides stay logically separate networks for pressure exactly as they do for items, bridged only by an `AdapterHookType` hook.

**The boundary partner matrix** - what the *other* hook facing the interface does, all keyed off the same `InterfaceHookState`:

- **`ProviderHookType` (now filtered)** - extract-only. `ProviderHookState` was changed to extend `SortingHookState` (like `FilterHookType`/`SyncHookType` already do, reusing `SortingHookMenu`/`SortingHookScreen` outright) so it can narrow *which* items it exposes, the same way a sync hook already could - `RequestFulfillment.fulfillFromProvider`'s existing `is SortingHookState` filter check picks this up for free. `ProviderSource.storage(level)` already reads whatever's directly across the hook's own face via `ItemApi.BLOCK.find`, so once the interface's stock is exposed there, this needed no new code at all beyond the state-shape change.
- **`FilterHookType`** - insert-only. Already a normal `validRoute` push candidate; the only missing piece was `PipeRouter.step` not folding a boundary edge into its BFS pass-through (see above) - once that's fixed, the existing `SortingHookState` filter/mode candidate check runs against the interface's own pass-through surface exactly like any other destination, and an accepted push is forwarded into the subnet on the interface's other side rather than staged.
- **`SyncHookType`** - filtered two-way, simply because it's already both a push candidate (`validRoute`) and a provider source (`providesItems`) on the *same* network today - facing an interface just means both of those now cross the seam instead of staying local: pushed items pass straight through into the subnet, pulled items leave the interface's `stock`.
- **`ExtractionHookType`** - actively pulls whatever's in the interface's stock out, on its own tick. `tryExtract`'s guard against pulling from "a pipe" (`level.getBlockState(neighborPos).block is PipeBlock` - true for any `HookBlockEntity`, interfaces included) is bypassed specifically when the neighbor is reached via a boundary edge.
- **`RequesterHookType`** - flips this hook's whole role from "stock the inventory next door" to "supply the interface", *keeping its own orders either way*. `tryRequest` detects a boundary partner via `SubnetBoundary.interfaceAt` and, if found, tops that interface's `stock` up to this hook's own `StockingRow` rather than the neighbouring inventory.

  **This is a reversal of how it originally worked, and the original was wrong.** It used to read the *interface's* target row and ignore its own, on the reasoning that both sides would then chase one number and never fight. But the interface already self-requests its own targets, so a requester pointed at one added nothing it was not already doing - and, worse, there was no way for the network on *this* side of a boundary to say what it was willing to push across, which is the only reason to put a requester there at all. The orders belong to the requester; the boundary is what they are served through.

  A requester only does this across a **real** crossing. An interface it can already reach through pipe - a run looping around the boundary rejoins the two sides into one subnet, however many interface hooks sit on the seam - leads nowhere new, so it does nothing at all and says so in its GUI; the interface likewise keeps stocking itself rather than standing down for an inert partner. Note this cannot be answered with `SubnetBoundary.isBoundaryEdge` (that is a local question) *or* with `RequestFulfillment.reachablePipes` (which returns every position it examined, boundary-blocked neighbours included, because its callers need adjacent non-pipes like warehouse controllers) - hence `RequestFulfillment.sharesSubnet`, which walks only positions genuinely reached.

    The oscillation that reasoning was guarding against is real, and is handled directly instead: while a requester is driving an interface, `InterfaceHookType` stands its own stocking down (`requisitionStock` returns early) and honours the requester's row in `drainExcess` (`externalRow`). One agreed target over one shared inventory, just sourced from the other side. Both rows being the same `StockingRow` type is what makes that substitution a one-liner rather than a translation.

## Deferred to playtesting / not blocking

- Priority as a 0–10 slider vs. discrete tiers (Highest/High/Normal/Low) — UX polish, not architecture.
- Filter matching by item identity only (not data components) — revisit if a use case needs it.
- **Default route uniqueness across a network merge**: `RoutingModule.DEFAULT_ROUTE_PRIORITY`'s "only one per network" is enforced only when a hook's priority is *set* (`UpdateSortingRoutingPacket` walks the network and clears any other holder) - not when two networks each already holding one get physically joined by a new pipe, since `PipeNetworkManager`'s merge path is deliberately hook-semantics-agnostic (pure topology bookkeeping). Post-merge, both survive until either is touched again; `PipeRouter.search`'s existing fewest-hops tie-break picks one deterministically in the meantime, so this is a soft inconsistency, not a crash or silent data loss. Revisit only if it proves to matter in practice.
