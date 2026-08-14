# M3 — Warehouse Storage

See [README.md](README.md) for shared conventions. This is the centerpiece milestone — deliberately not AE2-style abstract storage cells.

## Bounding volume

`WarehouseControllerBlockEntity` stores a `Bounds` (`min`/`max` `SBlockPos`, normalized regardless of click order - see `Bounds.of`) bound via a `WarehouseWandItem`: right-click two corners, then right-click the controller ([decision #2](README.md#resolved-architectural-decisions) — no literal sealed multiblock shell required). This is the deliberate tradeoff that makes large warehouses cheap to set up: a wand-defined volume reads less as a "physical multiblock" than a Mekanism-style built shell would, but doesn't punish scale.

Implemented as: the wand's own pending selection (`first`/`second` corner) lives on the wand `ItemStack` itself via `NBTHolder.item(stack)`, not any block entity, so it survives between the three clicks and travels with the stack. A third click against a `WarehouseControllerBlockEntity` while both corners are set commits `Bounds.of(first, second)` to it and clears the wand's selection; a click against anything else while both are already set restarts the selection from that click instead of getting stuck. `WarehouseControllerBlockEntity.bounds: Bounds?` is `null` until bound - wrapped in a private `BoundsSlot` data class the same way `ExtractionHookState`'s `ColorSlot` wraps its nullable color, since a bare nullable `NBTHolder.field` can't round-trip (see that type's KDoc).

## Gantry / crane

Modeled as a server-authoritative moving head `(pos: Vec3, progress)` owned by the controller — **not** a real `Entity`, purely simulated and periodically synced (`GantrySyncPacket`), the same dead-reckoning approach as M1's traveling items (avoids entity collision/pathfinding cost).

Motion model is deliberately **not** general 3D pathfinding: it's an idealized industrial gantry — move along X, then Z, then descend/ascend Y, exactly like a real overhead crane confined to its rail envelope. This is a hard constraint worth stating explicitly: **racks must leave the crane's overhead rail volume clear**. Much simpler to implement and reason about than voxel A* through a player-built warehouse, at the cost of constraining build layout.

**Ghost rail rendering** ([decision #3](README.md#resolved-architectural-decisions)): no physical rail/track blocks — the crane moves within the wand-defined envelope with no collision and no construction cost. The client renders a translucent/dashed rail overlay along the crane's computed travel path purely for visual readability. This needs:
- A client-only path-to-render-geometry step, recomputed when the bound volume changes or the crane's active rail segment changes (not every frame).
- A visual-style decision at implementation time (dashed line vs. faint rail-texture overlay vs. particle trail) — cosmetic, not architectural, left open until art passes happen.

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

- A pipe-network-facing `WarehouseInterfaceBlock`, which **queues** rather than instantly completing CSL calls — physical travel time is the point.
- The search/retrieval terminal (below).

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
