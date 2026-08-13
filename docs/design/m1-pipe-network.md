# M1 — Pipe Network Core

See [README.md](README.md) for the shared registry/CSL-adapter/GUI/`PressureConsumer` conventions this builds on.

## Blocks & block entities

- **`PipeBlock`** — the conduit. Six boolean connection `BooleanProperty`s (vanilla fence/pane-style dynamic `VoxelShape`), auto-connects to neighboring pipes and to any block exposing `ItemApi.BLOCK` storage. Implements [decision #1](README.md#resolved-architectural-decisions): a plain pipe is upgraded in place by an attachable module item (M2), not swapped for a different block.
- **`PipeBlockEntity : NBTBlockEntity`**:
  ```kotlin
  class PipeBlockEntity(pos: BlockPos, state: BlockState) : NBTBlockEntity(TileRegistry.Pipe, pos, state), PressureConsumer {
      val travelingItems by nbt.listField(TravelingItem.serializer()) { emptyList() }
  }
  ```
- **`ExtractorPipeBlock : PipeBlock`** — the *only* self-initiating block in the network. Every `extractionIntervalTicks` (config, default proposal 10, scaled by `onPressureTick` once M5 lands) it simulate-extracts from the adjacent `ItemApi.BLOCK` storage; on success spawns a `TravelingItem` into the network. Plain pipes are already valid insertion targets for anything adjacent to them — no separate "insertion pipe" block is needed (mirrors BuildCraft/Logistics Pipes precedent: dumb conduit vs. active extractor).

## Item representation in flight

```kotlin
@Serializable
data class TravelingItem(
    val stack: SItemStack,     // Archie's @Contextual ItemStack typealias, backed by CodecSerializer(ItemStack.CODEC)
    val fromDirection: @Serializable(with = DirectionSerializer::class) Direction,
    var progress: Float = 0f,  // 0f..1f across the current pipe segment
    var path: List<SBlockPos> = emptyList(),
)
```

`SItemStack`/`SBlockPos` are Archie's own contextual-serializer typealiases (`net.kernelpanicsoft.archie.serialization.serializers`) — already wired into the format's `SerializersModule`, so no bespoke item-id/component encoding is needed. `DirectionSerializer` is a small local `KSerializer<Direction>` (Archie doesn't ship one) encoding the enum by name, the same pattern Archie's own `BlockHitResultSerializer` uses inline for its `side` field. At the storage layer, conversion to/from CSL's `ItemResource` (needed to call `CommonStorage<ItemResource>.insert`/`.extract`) happens via `ItemResource.of(stack)`/`resource.toStack(count)` at the point of insertion/extraction, not on `TravelingItem` itself.

## Tick model

Each `PipeBlockEntity` with a non-empty `travelingItems` list is an active ticker. Per server tick:

1. `progress += speed` (speed = config-driven ticks-per-segment × `onPressureTick` multiplier once M5 exists; v1 default ≈ 1 block/second).
2. At `progress >= 1f`:
   - Next hop is another pipe → move the item to that `PipeBlockEntity`'s list, `progress = 0f`.
   - Next hop is a resolved inventory endpoint → attempt `storage.insert(resource, amount, simulate = false)` via `ItemApi.BLOCK.find`.
3. On insertion failure (full/rejected): the item **stalls at `progress = 1f`** and retries insertion every subsequent tick — no backoff in v1 (flagged for playtesting). If the route becomes permanently invalid (target endpoint removed) and no alternate route exists, the item **jams**: eject as a dropped-item entity at the stalled pipe. Thematically appropriate (a jammed tube), and avoids items silently vanishing.

**Implementation note**: `travelingItems` is fetched exactly once per `tick()` call and mutated only through index-based `set`/`removeAt` (`TravelingItem.copy(...)` standing in for field mutation) — see [README.md](README.md#gotcha-archies-listfieldmapfieldfield-only-persist-through-structural-mutation) for why in-place field mutation and iterator-based removal don't persist against Archie's `listField`.

**Client sync**: server is authoritative. A lightweight `PipeContentsSyncPacket(pos, items: List<TravelingItemDto>)` is broadcast via `toNearPlayers` periodically (e.g. every 4 ticks, or immediately on a hop) rather than every tick; the client interpolates `progress` locally between syncs (same dead-reckoning idea as vanilla entity motion), avoiding a packet-per-item-per-tick cost.

## Pipe network graph

`PipeNetworkManager` — one instance per `ServerLevel` (`WeakHashMap`-cached, not a persisted `SavedData`) — owns `BlockPos → networkId` and `networkId → PipeNetwork`. State is derived entirely from currently-loaded pipes, populated via `ensureRegistered` called idempotently at the top of every pipe's own `tick()` (there's no dedicated "block entity now active" lifecycle hook to call it from instead), rather than from serialized graph data:

```kotlin
class PipeNetwork(val id: UUID) {
    val members: MutableSet<BlockPos> = hashSetOf()
    var version: Int = 0   // bumped on any topology or module change; routing cache key
}
```

- **Placement (merge)**: **union-find** (disjoint-set) over network ids — O(α(n)) per placement, checking the 6 neighbors' existing network ids and unioning them. Exactly right for "grows constantly, rarely shrinks."
- **Removal (possible split)**: union-find can't cheaply detect splits, so removal marks the network dirty and schedules a **chunked BFS rebuild** spread across ticks (budget e.g. 500 blocks/tick, same pattern M3's warehouse scan uses) from each remaining neighbor, assigning fresh network ids to whichever connected components result. Avoids a full-network single-tick stall on large builds while staying simple to reason about — no incremental cut-vertex bookkeeping.

## Routing (M1 = unweighted)

Not per-item Dijkstra. Each extractor's pull resolves a target via plain BFS from the source pipe over live pipe blockstate (not `PipeNetwork.members` directly — the network object is only consulted for the routing-cache key), testing each pipe-adjacent inventory with a **simulated** insert (`simulate = true`) until one accepts. The inventory the extractor is pulling *from* is passed as an `exclude` position and never considered a candidate — without this, a source with room for more of the same item (the common case, right after extracting from it) routes the item straight back to itself, which re-extracts it next cycle and never actually delivers anything. Result is cached per `(networkId, network.version, resource, exclude)` and invalidated automatically whenever `version` changes. M2 upgrades the cache key and candidate selection to weighted priority + color without touching this BFS machinery — see [m2-sorting-routing.md](m2-sorting-routing.md).

Sync is skipped entirely for a pipe that's idle (no items, nothing hopped this tick) rather than pinging empty state on the 4-tick timer regardless — every placed pipe ticks, so unconditional periodic sync means every idle pipe on a build broadcasts nothing-happened packets forever.

## Deferred to playtesting / not blocking

- Stall/backpressure behavior tuning beyond "retry every tick, then jam."
- Pipe tier speed/art (brass/copper/lead, speed-per-tier).
- Routing only excludes the immediate extraction source, not general cycles elsewhere in the network (e.g. a loop that could route an item back to its origin via a different path) — not handled yet.
