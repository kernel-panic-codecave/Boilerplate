# M2 — Sorting & Routing

See [README.md](README.md) for shared conventions and [m1-pipe-network.md](m1-pipe-network.md) for the pipe network/BFS routing this extends — M2 does not add a new network type, it extends the pipe.

## Hook taxonomy

M2's `SortingHookType`/`ExtractionHookType` and M3's `RequesterHookType`/`ProviderHookType` (see [m3-warehouse-storage.md](m3-warehouse-storage.md#request-based-routing-logistics-pipes-requestprovider-pipes)) fall into a clean 2x2 once all four exist - source vs. sink, crossed with self-initiating ("active") vs. only-consulted-when-something-else-acts ("passive"):

|                    | **Active** (initiates every tick/interval) | **Passive** (only acts when consulted) |
|--------------------|---------------------------------------------|------------------------------------------|
| **Provider** (source) | `ExtractionHookType` - pushes out on its own interval | `ProviderHookType` - available when a request finds it |
| **Requester** (sink)  | `RequesterHookType` - issues standing-order pulls | `SortingHookType` - a push-model candidate destination, ranked by filter/priority |

Not a design decision that shaped any one of the four - it fell out after M3's request-based hooks landed - but worth naming since it's a useful lens for where a *new* hook kind belongs: does it originate transfers on its own, or only respond to one already in flight; and is it a source or a sink for the resource in question.

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

## Default route (Logistics Pipes homage)

`RoutingModule.DEFAULT_ROUTE_PRIORITY` (`-1`) is a reserved sentinel below the normal `0`..`10` priority range - Logistics Pipes' Default Route, a catch-all sink that only wins when nothing else on the network accepts an item. Needed **zero** changes to `PipeRouter.search`'s candidate evaluation: `module.priority` was already a plain, sign-agnostic `Int` comparison, so a sorting hook set to `-1` (typically paired with blacklist mode and an empty filter, to accept anything) naturally loses to every other candidate and only gets chosen when it's the sole option. Any sorting-hook-guarded destination can claim it this way - including a bound warehouse's staging buffer (see [m3-warehouse-storage.md](m3-warehouse-storage.md)), with no warehouse-specific routing code needed either.

Only one sorting hook per network may hold the sentinel at a time: `UpdateSortingRoutingPacket.handleOnServer`, when applying an edit that sets it, walks the network's members and resets any *other* hook already holding it back to `0`. Enforced at assignment time only, not on network-topology changes - see "Deferred to playtesting" below for the merge edge case this leaves open.

Caught a real, previously-latent bug along the way: `FilterMode` (a plain Kotlin enum) relied on kotlinx.serialization's default `encodeEnum`, which knbt's `AbstractNbtEncoder` never overrides - it silently "worked" only because `FilterMode.WHITELIST` is the field's declared default, which kotlinx.serialization skips encoding entirely; `BLACKLIST` (or any explicit non-default enum value nested this way) crashed the instant something actually tried to persist it. Fixed with `FilterModeSerializer`, encoding by name via `encodeString`/`decodeString` - the same workaround `DirectionSerializer`/`DyeColorSerializer` already use for the same underlying knbt gap.

## Future: hook-to-hook/hook-to-pipe facing as a subnet boundary (not started)

Today `PipeNetworkManager` merges every reachable, matching pipe into one flat graph regardless of what's attached to it — a hook is just a per-face attachment *within* that one network, uniformly visible to `PipeRouter.search` from anywhere else in it. Idea for later: when a hook faces directly into *another* hook, or into another pipe network, rather than into a plain inventory, treat that facing as a deliberate **subnet boundary** instead of a topology merge — the two sides stay logically separate networks, and routing across the boundary is gated by whatever hook(s) sit at the junction (their type/filter), not by BFS reachability alone.

The concrete example: a `ProviderHookType` hook facing into a `SortingHookType` hook (acting as a filter) at the seam between two networks would only expose items matching that filter across into the other side — the rest of the provider's network stays invisible from there. This is the same shape as AE2's Storage Bus attached to an Interface: a narrow, filtered, deliberate bridge between two otherwise-independent ME networks, rather than the two networks becoming one.

Open questions to resolve whenever this gets picked up, not blocking anything current:

- Does *every* hook facing another hook/pipe become a boundary, or only specific combinations (e.g. only when the facing hook is itself filtering something, like Provider→Sorting)? A hook facing another hook with no filter at all might reasonably still just merge/pass through.
- Where does this live mechanically - does `PipeNetworkManager` need a real notion of "two adjacent but unmerged networks bridged by a filtered edge," or can `PipeRouter.search` treat a boundary hook as a special candidate that, once entered, re-launches a second BFS on the far side under the boundary hook's own filter (cheaper to reason about, no change to merge semantics)?
- Interacts with the default-route sentinel (`RoutingModule.DEFAULT_ROUTE_PRIORITY`) and M3's request-based routing (`RequesterHookType`/`ProviderHookType`) - a request crossing a subnet boundary is presumably exactly the AE2 Storage-Bus-on-Interface case, so whatever `RequestFulfillment` does needs to understand boundaries too, not just `PipeRouter.search`'s push-model path.

## Deferred to playtesting / not blocking

- Priority as a 0–10 slider vs. discrete tiers (Highest/High/Normal/Low) — UX polish, not architecture.
- Filter matching by item identity only (not data components) — revisit if a use case needs it.
- **Default route uniqueness across a network merge**: `RoutingModule.DEFAULT_ROUTE_PRIORITY`'s "only one per network" is enforced only when a hook's priority is *set* (`UpdateSortingRoutingPacket` walks the network and clears any other holder) - not when two networks each already holding one get physically joined by a new pipe, since `PipeNetworkManager`'s merge path is deliberately hook-semantics-agnostic (pure topology bookkeeping). Post-merge, both survive until either is touched again; `PipeRouter.search`'s existing fewest-hops tie-break picks one deterministically in the meantime, so this is a soft inconsistency, not a crash or silent data loss. Revisit only if it proves to matter in practice.
