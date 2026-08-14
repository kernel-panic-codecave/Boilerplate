# M3 — Warehouse Storage

See [README.md](README.md) for shared conventions. This is the centerpiece milestone — deliberately not AE2-style abstract storage cells.

## Bounding volume

`WarehouseControllerBlockEntity` stores a `Bounds` (`min`/`max` `SBlockPos`, normalized regardless of click order - see `Bounds.of`) bound via a `WarehouseWandItem`: right-click two corners, then right-click the controller ([decision #2](README.md#resolved-architectural-decisions) — no literal sealed multiblock shell required). This is the deliberate tradeoff that makes large warehouses cheap to set up: a wand-defined volume reads less as a "physical multiblock" than a Mekanism-style built shell would, but doesn't punish scale.

Implemented as: the wand's own pending selection (`first`/`second` corner) lives on the wand `ItemStack` itself via `NBTHolder.item(stack)`, not any block entity, so it survives between the three clicks and travels with the stack. A third click against a `WarehouseControllerBlockEntity` while both corners are set commits `Bounds.of(first, second)` to it and clears the wand's selection; a click against anything else while both are already set restarts the selection from that click instead of getting stuck. `WarehouseControllerBlockEntity.bounds: Bounds?` is `null` until bound - wrapped in a private `BoundsSlot` data class the same way `ExtractionHookState`'s `ColorSlot` wraps its nullable color, since a bare nullable `NBTHolder.field` can't round-trip (see that type's KDoc).

The third click is rejected (a "no" sound, selection left pending) unless the controller itself sits on the resulting footprint's border, inline with the outer rail lines (`Bounds.isOnBorder`) - the gantry's rail envelope is confined to the bound volume itself, so a controller off that border would be a delivery destination the crane can't physically reach. See [Gantry / crane](#gantry--crane) below for the frame this border constraint lines up with.

## Gantry / crane

Modeled as a server-authoritative moving head `(pos: Vec3, progress)` owned by the controller — **not** a real `Entity`, purely simulated and periodically synced (`GantrySyncPacket`), the same dead-reckoning approach as M1's traveling items (avoids entity collision/pathfinding cost).

Motion model is deliberately **not** general 3D pathfinding: it's an idealized industrial gantry — move along X, then Z, then descend/ascend Y, exactly like a real overhead crane confined to its rail envelope. This is a hard constraint worth stating explicitly: **racks must leave the crane's overhead rail volume clear**. Much simpler to implement and reason about than voxel A* through a player-built warehouse, at the cost of constraining build layout.

**Frame + moving crossbeams** ([decision #3](README.md#resolved-architectural-decisions), BuildCraft Quarry-style): binding a warehouse auto-places a real, static `GantryRailBlock` perimeter frame — a hollow rectangle traced around the bound footprint's border at rail height (`Bounds.railPerimeter`, `bounds.max.y`) — and removes the old one on rebind/unbind (`WarehouseControllerBlockEntity.bounds`'s setter). `GantryRailBlock` is a six-way connecting block, the same shape/pattern as `PipeBlock`, but not part of any pipe network — it only connects to its own kind (and the controller, so the frame reads as attached to it). Real placed blocks need no sync code of their own; ordinary chunk updates carry them to clients for free.

Only the parts that change every tick stay a client-only dynamic render (`WarehouseControllerBlockEntityRenderer`, dead-reckoned from `GantryClientCache`, itself fed by `GantrySyncPacket` carrying the full `Bounds` alongside the gantry's position/path): two crossbeams, one spanning the footprint's full X extent at the head's current Z, the other spanning the full Z extent at the head's current X, intersecting directly above wherever the head is — plus the vertical drop rod connecting down to the head, and the head itself. The crossbeams/rod reuse `GantryRailBlock`'s own baked model (looked up against a synthetic connected `BlockState`, not a placed block) so they read as an extension of the real frame; the head keeps its own placeholder model (`ItemRegistry.GantryHead`, a fake item that exists purely as a bake target).

## Rack scanning & index

Any block inside the bound volume that exposes `ItemApi.BLOCK` storage — vanilla chest/barrel/shulker, another mod's inventory, or Tubular Storage's own machines — is automatically a "rack." This is what satisfies "no proprietary storage-cell skin": CSL's uniform `CommonStorage<ItemResource>` view means the index doesn't care what the block actually is.

```kotlin
class WarehouseIndex {
    val locations: MutableMap<ItemResource, MutableList<RackSlotRef>> = hashMapOf()
    data class RackSlotRef(val pos: BlockPos, val direction: Direction?, var amount: Long)
}
```

Built by iterating the bound volume, calling `ItemApi.BLOCK.find` per block, then walking `CommonStorage.size()`/`get(i)`/`StorageSlot.getResource()`/`.getAmount()`. This **is** the "reuse CSL for the indexing layer" ROADMAP.md flagged for evaluation, and it's a clean yes: CSL is the read/introspection surface, not the storage representation — racks remain the player's own physical blocks.

- Full rescans are chunked across ticks (budget e.g. 4096 blocks/tick, same pattern as M1's network rebuild) and volume-capped via config to bound worst-case cost.
- Steady-state updates are **not** rescans: each gantry pick/place mutates the affected `RackSlotRef` in O(1).
- A low-frequency background "audit" rescan corrects drift from players manually touching racks by hand (bypassing the gantry).

## Job queue

Single crane per controller in v1 — multi-gantry parallelism is a stretch upgrade, not core scope. `ArrayDeque<GantryJob>` where a job is an insert or extract request against a `RackSlotRef` chosen from the index (nearest by travel time, or first-fit). Requests arrive from:

- The pipe network, via the controller itself — no separate interface block. `WarehouseControllerBlockEntity` exposes a small staging `ArchieItemStorage` buffer through `ItemApi.BLOCK` (the standard "expose once at block-entity-type registration" pattern, see `README.md#csl-storage-adapter-pattern`), which is what actually **queues** rather than instantly completing CSL calls — a gantry job has to physically retrieve/stow before the buffer's contents change, and physical travel time is the point.
  - *Inbound* (pipe → warehouse) needs no warehouse-specific code at all: the staging buffer is just another `ItemApi.BLOCK`-accepting destination as far as `PipeRouter.findRoute`/`ExtractionHookType` are concerned, so M1/M2 already route deliveries into it. The controller notices new contents in the buffer on its own tick and enqueues a put-away job into the best rack.
  - *Outbound* (warehouse → requester, see Request-based routing below) does need controller-side code: once a retrieval job lands the item in the staging buffer, the controller checks its own six neighbors for a connected `PipeBlockEntity` and injects a `TravelingItem` directly into *that* pipe's own queue - the same thing `ExtractionHookType.tryExtract` does to its own tile, just initiated by the controller instead of a hook.
- The search/retrieval terminal (below).
- A **defrag** request (terminal button or standalone item, TBD): for each `ItemResource` with more than one `RackSlotRef` in the index, enqueues move-jobs consolidating it into the fewest slots/racks (biggest partial stacks absorb the smallest first), freeing up whole racks the same way disk defragmentation frees contiguous space. Purely a batch of ordinary insert/extract jobs against the existing queue — no new gantry/job machinery needed, just a planner that reads `WarehouseIndex.locations` and emits jobs. Runs opportunistically (low job-queue priority) rather than blocking other requests, since it's housekeeping, not time-critical.

### Request-based routing (Logistics Pipes' Request/Provider Pipes)

M2's extraction/sorting hooks are **push**-based: an extractor decides what to pull and the network finds *any* accepting destination. Fulfilling a specific item request needs the opposite: find a *source* that has the item and route it to one *specific* destination, the requester. Two new `PipeHookType`s, alongside M2's extraction/sorting hooks:

- **`RequesterHookType`**: placed on a pipe face, declares a standing order - one `ItemResource` plus a target quantity to keep stocked in the adjacent inventory. Ticks the same way `ExtractionHookType` does: if the adjacent inventory's current amount is below target, issues a request for the shortfall. (One-off, browse-and-request-anything fulfillment is the terminal's job, not this hook's - see Terminal below.)
- **`ProviderHookType`**: placed on a pipe face touching a chest, opts that inventory into being pullable by network requests. Deliberately explicit rather than "every reachable inventory is automatically a source" ([resolved](README.md#resolved-architectural-decisions) - matches Logistics Pipes' actual Provider Pipe, and this mod's stated non-AE2 direction) - purely passive, no periodic tick behavior of its own, only consulted when a request needs resolving.

Resolving a request for `resource`/`amount` from `requesterPos`:

1. Search the network's `ProviderHookType` hooks for one whose adjacent inventory currently has `resource` in stock. If found, extract from it (same mechanics as `ExtractionHookType.tryExtract`) and route a `TravelingItem` to `requesterPos`.
2. Otherwise, search bound warehouses reachable from the network whose `WarehouseIndex.locations` has `resource`. If found, enqueue a `GantryJob` retrieving it into that warehouse's own staging buffer, from which the controller spawns the `TravelingItem` as described above.
3. Otherwise, the request stays unfulfilled and retries on `RequesterHookType`'s next tick (or, for a terminal request, surfaces as "unavailable").

Both cases need `PipeRouter.findRouteTo(level, from, to): List<BlockPos>?` - a new routing mode alongside the existing `findRoute` (any accepting destination). This one's simpler than `findRoute`: with a known target, it's a shortest path *to* that position through the pipe network, not an evaluate-every-candidate search - no per-candidate filter/color/priority weighing needed along the way (so no `resource` parameter either, unlike `findRoute`), since the destination isn't being chosen, it's given.

Implemented as `RequesterHookState`/`ProviderHookState` (both under `pipe/hook/`, alongside M2's hooks) and `RequestFulfillment` (under `pipe/network/`, alongside `PipeRouter`) - `RequesterHookState.request` is a single item-storage slot doing double duty as the whole config: an empty slot means no standing order, a filled one's item identity and *stack count* are the requested resource and the quantity to keep stocked, no separate amount field or dedicated GUI needed.

## Terminal

`WarehouseTerminalBlock`/`Menu`:
- A `BasicTextField` search box + a `Scrollable` **virtual** result list (custom composable rendering item icon/count rows, not real vanilla `Slot`s — warehouse contents vastly exceed the ~45-slot vanilla menu ceiling), reading `WarehouseIndex` directly.
- A "withdraw" button enqueues an extract job targeting the requesting player's inventory, wrapped as a CSL sink via `AbstractVanillaContainer` — the same CSL vanilla-container adapter `ComposeContainerMenuBase` already uses internally for plain-`Container` slots.
- Multiple requested items in one withdrawal batch into a single crane run rather than returning the crane "home" between each item, for usability.

## Rack types

Three built-in rack block families, distinguished by storage strategy rather than capacity alone (addon mods can register more, since "rack" is just "anything the bound volume finds exposing `ItemApi.BLOCK`" - see above):

- **Bulk/deep storage** — a large quantity of a single item, minimal per-slot overhead. For warehousing stacks-of-stacks of one resource (cobblestone, dirt, ore) without the index needing to track many separate `RackSlotRef`s for what's conceptually one pile.
- **General storage** — ordinary multi-item shelving, closest to a plain chest/barrel's semantics, just at warehouse scale.
- **Unstackable storage** — purpose-built for the NBT-heavy, `maxStackSize == 1` case (enchanted tools/bows, written books, etc. - the mob-farm-drops problem) so a warehouse doesn't either reject them or accumulate the chunk-data bloat vanilla containers full of unique-NBT single-count stacks cause. Candidate strategy: dedupe by item id + full data components, storing one record with a count rather than one `ItemStack` per physical item - two drops with identical enchantments/components collapse into one entry, and only genuinely distinct component sets get their own record.

## Deferred to playtesting / not blocking

- Multi-gantry parallelism as a later upgrade (not core M3 scope).
- Exact rack block implementation (block entity shape, capacity tuning, addon-mod registration surface) - noted here as a placeholder ahead of M3 design work proper.
