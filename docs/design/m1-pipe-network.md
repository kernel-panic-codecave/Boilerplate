# M1 — Pipe Network Core

See [README.md](README.md) for the shared registry/CSL-adapter/GUI/`PressureConsumer` conventions this builds on.

## Blocks & block entities

- **`PipeBlock`** — the *only* pipe block. Six boolean connection `BooleanProperty`s (vanilla fence/pane-style dynamic `VoxelShape`), auto-connects to neighboring pipes and to any block exposing `ItemApi.BLOCK` storage. There is no separate extractor/sorting block — see hooks below, which implement and generalize [decision #1](README.md#resolved-architectural-decisions) ("a plain pipe is upgraded in place, not swapped for a different block") to per-face rather than whole-block.
- **`PipeBlockEntity : NBTBlockEntity`**:
  ```kotlin
  class PipeBlockEntity(pos: BlockPos, state: BlockState) : NBTBlockEntity(TileRegistry.Pipe, pos, state), PressureConsumer {
      val travelingItems by nbt.listField(TravelingItem.serializer()) { emptyList() }
      val hooks by nbt.mapField(HookState.serializer()) { emptyMap() }   // keyed by Direction.name
  }
  ```

### Hooks: AE2-import-bus-style attachments, not separate blocks

A pipe carries zero or more **hooks** — one per face, each an attachment rather than a distinct block, so a single pipe segment can carry an extraction hook on one face and a sorting hook on another simultaneously. `PipeHookType` is the behavior for a *kind* of hook (extraction, sorting, and whatever M3+ adds - a valve, a gauge); `HookState` (`type` id + `ticksSinceExtraction`) is the small per-face persisted payload stored in `PipeBlockEntity.hooks`, keyed by `Direction.name`. Each face's `RoutingModule` (filter mode/priority/color) lives separately, in six single-value fields rather than inside `HookState` - see [m2-sorting-routing.md](m2-sorting-routing.md#module-data) for why.

- **Registry, not a hardcoded enum**: `PipeHookType` entries live in a genuine custom Minecraft registry (`tubularstorage:pipe_hook_type`, declared via Archie's `RegistrarHelper` and populated via `HookTypeRegistry : ADeferredRegistryHolder<PipeHookType>`), synced to clients. A new hook kind - including an addon mod's own, under its own namespace - is just another registry entry, not a new block/block-entity pair. `HookState.type` is accordingly a full `ResourceLocation` (`SResourceLocation`), not a bare name assumed to live under `tubularstorage`. Runtime lookup by id goes through `HookTypeRegistry.byId(ResourceLocation)`, which resolves via the live `Registrar.get(ResourceLocation)` rather than `ADeferredRegistryHolder`'s own bookkeeping `Map` - the latter is populated at `register()` call time regardless of whether the underlying custom registry has actually processed that registration yet, so it isn't reliable for a runtime lookup (confirmed the hard way: it returned null for every hook during M2 gametest work, silently no-op'ing every hook tick).
- **Placement**: a `HookItem` (e.g. the extraction hook item, the sorting hook item) right-clicked against a placed `PipeBlock` attaches its hook to whichever face `BlockHitResult.direction` reports, if that face doesn't already carry one - `PipeBlock.useItemOn`. Shift-right-click empty-handed on a hooked face removes it; empty-hand right-click otherwise opens that hook's GUI, if it has one (`PipeHookType.hasMenu`) - `PipeBlock.useWithoutItem`.
- **Extraction hook (`ExtractionHookType`)** — the *only* self-initiating hook in the network: every `EXTRACTION_INTERVAL_TICKS` it simulate-extracts from the `ItemApi.BLOCK` storage on its own attached face only; on success spawns a `TravelingItem` into the network. Fixing the pull side to one specific face, rather than the old M1 design's "any adjacent inventory is a fair source," is what makes source and destination distinguishable at all: without it, a bare inventory on a different face that just received an item looks exactly as "extractable" as the real source on the hook's very next cycle, and the two oscillate the stack back and forth forever (caught as a genuine bug, not just a design smell, during M2 gametest work).
- **Sorting hook (`SortingHookType`)** — see [m2-sorting-routing.md](m2-sorting-routing.md).
- **Rendering**: each hook's baked model is authored once facing north and rotated onto the actual attached face by `PipeHookBlockEntityRenderer` at render time (`PoseStack` rotation around the block center) - one model per hook type serves all six faces. The model id itself is a *convention* derived from the hook type's own id (`mymod:extraction` → `mymod:block/extraction_hook`), not a lookup table the renderer has to know every hook type to populate - an addon's own hook type gets a working model reference for free as long as it follows the convention. Currently placeholder cuboid geometry/flat-color textures; the pipe body itself is a vanilla `"multipart"` blockstate model keyed to the same six connection booleans. Hand-written JSON for now - datagen is a planned follow-up, not yet implemented.

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

Not per-item Dijkstra. Each extraction hook's pull resolves a target via plain BFS from its pipe over live pipe blockstate (not `PipeNetwork.members` directly — the network object is only consulted for the routing-cache key), testing each pipe-adjacent inventory with a **simulated** insert (`simulate = true`) until one accepts. The inventory the hook is pulling *from* (its own attached face) is passed as an `exclude` position and never considered a candidate — without this, a source with room for more of the same item (the common case, right after extracting from it) routes the item straight back to itself, which re-extracts it next cycle and never actually delivers anything. Result is cached per `(networkId, network.version, resource, exclude)` and invalidated automatically whenever `version` changes. M2 upgrades the cache key and candidate selection to weighted priority + color without touching this BFS machinery — see [m2-sorting-routing.md](m2-sorting-routing.md).

Sync is skipped entirely for a pipe that's idle (no items, nothing hopped this tick) rather than pinging empty state on the 4-tick timer regardless — every placed pipe ticks, so unconditional periodic sync means every idle pipe on a build broadcasts nothing-happened packets forever.

## Deferred to playtesting / not blocking

- Stall/backpressure behavior tuning beyond "retry every tick, then jam."
- Pipe tier speed/art (brass/copper/lead, speed-per-tier).
- Routing only excludes the immediate extraction source, not general cycles elsewhere in the network (e.g. a loop that could route an item back to its origin via a different path) — not handled yet.
- Real hook/pipe models and textures — current geometry (a north-facing cuboid nub per hook, rotated in place; a multipart cuboid-arm pipe body) and flat-color textures are placeholders.
- Datagen for blockstates/models (currently hand-written JSON under `common/src/main/resources`).
- Two Archie `@Sync` bugs found while building this, both worked around rather than patched upstream this pass: (1) `NBTHolder.mapField`/`listField` register the wrong serializer for `@Sync` (element instead of collection), crashing on sync; (2) `BlockEntityStateContainer`'s reflective property scan keys by the raw Kotlin property name while `NBTHolder`'s manual registration/dirty-tracking keys by `property.name.toSnakeCase()`, so a multi-word `@Sync` scalar field (e.g. `routingNorth`) also silently desyncs. `PipeBlockEntity.routing: FaceRouting` (a single, single-word field) avoids both. Fix upstream in Archie, then simplify back down once fixed.
