# Boilerplate — Design Docs

Implementation-level design for `ROADMAP.md`'s milestones. Each milestone gets its own file; this one covers the foundations shared across all of them so the per-milestone docs aren't repeating themselves.

- [M1 — Pipe Network Core](m1-pipe-network.md)
- [M2 — Sorting & Routing](m2-sorting-routing.md)
- [M3 — Warehouse Storage](m3-warehouse-storage.md)
- [M4 — Crafting Automation](m4-crafting-automation.md)
- [M5 — Steampunk Power Layer](m5-pressure-power.md)
- [M6 — Polish & Parity](m6-polish-parity.md)

All of this is grounded in Archie's and `earth.terrarium.common_storage_lib`'s (CSL) real, verified APIs — no invented classes or method names. This is the spec each milestone gets built against; M1/M2 are implemented, M3 is in progress, M4+ aren't started yet.

## Package layout

Under `common/src/main/kotlin/net/kernelpanicsoft/boilerplate/`:

```
registry/    BlockRegistry, ItemRegistry, TileRegistry, GuiRegistry, SoundRegistry, ParticleRegistry
config/      Config.kt
network/     BoilerplateNetworkChannel + packet data classes
pipe/        network/ (graph+routing, incl. shared AbstractPipeNetwork(Manager) base), block/, entity/
warehouse/   controller, index, gantry, terminal
crafting/    pattern, resolver, provider/assembly blocks
power/       network/ (pressure-specific AbstractPipeNetwork(Manager) implementation + PressureNetworkBoundary), block/ (PressurePipeBlock), tank/compressor encasement types (PipeEncasementType entries, same hierarchy pipe/encasement/ defines)
client/      Compose screens/renderers
datagen/     gated via Platform.isDevelopmentEnvironment()
gametest/    gated via AGameTestPlatform
```

## Resolved architectural decisions

These came up during design as genuine forks and were decided with the user — treat them as settled, not open:

1. **Pipe upgrades (M1/M2)**: an attachable module item unlocks sorting/filtering/color behavior on a plain pipe. Not separate pipe-per-behavior blocks.
2. **Warehouse bounds (M3)**: a `WarehouseWandItem` defines the storage volume by right-clicking two corners, then binding to the controller. No literal built shell required.
3. **Gantry rails (M3)**: BuildCraft Quarry-style, not a pure rendering trick — binding a warehouse auto-places a real, static `GantryRailBlock` perimeter frame around the bound footprint at rail height (removed on rebind/unbind), a six-way connecting block the same shape as `PipeBlock` but not part of any pipe network. Only the parts that change every tick — the two crossbeams tracking the head's current X/Z, the vertical drop rod, and the head itself — stay a client-only dynamic render instead of real blocks. `WarehouseControllerBlock` must sit on the bound footprint's border, inline with the outer rail lines, since the gantry can't reach outside its own rail envelope to reach it otherwise.
4. **Crafting execution (M4)**: no dedicated crafting machine. A **Crafting Buffer** encasement wraps a pipe segment; adjacent encased segments cluster into one shared-capacity "Crafting CPU" (only when arranged as a complete cuboid — 1x1x1, 2x2x1, 2x2x2… AE2's formation rule) that claims a whole plan's raw materials up front, then shuttles each step's ingredients out to whatever `PatternProviderHookType` hook can produce it and pulls the result back. A vanilla crafting table resolves instantly (the pattern was assembled once, at encode time); a real self-driving machine takes whatever time it takes, which is what M5's pressure mechanic gates.
5. **Request fulfillment sources (M3)**: item requests (from a `RequesterHookType` or the terminal) are only fulfillable from chests explicitly opted in via a `ProviderHookType`, or from a bound warehouse - not from every inventory the pipe network happens to reach. Matches Logistics Pipes' actual Provider Pipe (explicit opt-in), not an AE2/Refined-Storage-style "everything connected is automatically storage" model.

Deferred and *not* blocking any of this: exact balance numbers (extraction interval, gantry speed, pressure costs — playtesting), whether over-pressure should be dangerous (M5, optional stretch), and exact block names/textures/art.

## Gotcha: Archie's `listField`/`mapField`/`field` only persist through structural mutation

`NBTHolderImpl`'s property getter for `by listField(...)` (and `mapField`/`field`) **decodes a fresh copy from storage on every single access** — it does not hand back a stable reference. Persistence happens only through `ObservableList`'s intercepted structural operations (`add`, `removeAt`, `set`, `clear`, ...), each of which re-encodes the whole collection back into storage. Two patterns silently do nothing as a result:

- Mutating a `var` field on an element already inside the list (e.g. `item.progress += x`) — not a structural operation, so `ObservableList` never sees it. The mutation lands on a throwaway copy; the next access re-decodes from the unchanged backing storage as if it never happened.
- Removing via an `Iterator` (`iterator.remove()`) — `ObservableList` doesn't override `iterator()`, so removal isn't observed either.

Correct pattern: fetch the property **exactly once** per use (into a local `val`), and touch it only through the intercepted operations — index-based `list[i] = element.copy(...)` and `list.removeAt(i)`, never in-place field mutation or iterator removal. This bit M1's `PipeBlockEntity` tick loop for real (`TravelingItem.progress` silently never advanced) before being caught and fixed — see [m1-pipe-network.md](m1-pipe-network.md).

## Registries

One `ADeferredRegistryHolder<T>` singleton per registrable type, mirroring Archie's own `test/` example mod exactly:

```kotlin
object BlockRegistry : ADeferredRegistryHolder<Block>(Boilerplate.MOD, Registries.BLOCK) {
    val SomePipe by register("some_pipe") { PipeBlock(blockProperties(Blocks.IRON_BLOCK) { }) }
}
object ItemRegistry : ADeferredRegistryHolder<Item>(Boilerplate.MOD, Registries.ITEM) { /* ... */ }
object TileRegistry : ADeferredRegistryHolder<BlockEntityType<*>>(Boilerplate.MOD, Registries.BLOCK_ENTITY_TYPE) { /* ... */ }
object GuiRegistry : ADeferredRegistryHolder<MenuType<*>>(Boilerplate.MOD, Registries.MENU) {
    override fun initClient() { MenuRegistry.registerScreenFactory(SomeMenu, ::SomeScreen) }
}
```

Later: `SoundRegistry`, `ParticleRegistry` (both `ADeferredRegistryHolder`, same pattern). All `.init()` calls happen from `Boilerplate.init()`; `GuiRegistry.initClient()` (and any other registry needing client-side registration) is called from `Boilerplate.initClient()`.

## CSL storage adapter pattern

Used identically by every milestone that touches item/fluid/pressure storage — don't deviate from this per-milestone:

- **Reading/writing a neighboring block** (vanilla chest, another mod's machine, another Boilerplate machine) always goes through CSL's lookup:
  ```kotlin
  val storage: CommonStorage<ItemResource>? = ItemApi.BLOCK.find(level, pos, state, blockEntity, direction)
  ```
  This is what makes pipes/gantry/patterns interoperate with vanilla and third-party inventories for free — CSL's Fabric backend bridges to `ItemStorage.SIDED` (Fabric Transfer API), its NeoForge backend bridges to `Capabilities.ItemHandler.BLOCK` (`IItemHandler`), both transparently.
- **A Boilerplate block's own buffer** is always declared via `NBTHolder.itemField(size)` — this yields an `ArchieItemStorage`, which is already an NBT-persisted `CommonStorage<ItemResource>` (Archie ships this, no need to hand-roll a `CommonStorage` implementation for our own machines). The pressure/energy analogue is `NBTHolder.energyField(capacity)` → `ArchieEnergyStorage : ValueStorage`. Both are exposed outward *once*, at block-entity-type registration time:
  ```kotlin
  TileRegistry.SomeTile.exposeItemStorage { tile -> tile.buffer }       // ArchieCapabilityExposure
  TileRegistry.PressureTank.exposeEnergyStorage { tile -> tile.pressure }
  ```
- **All movement** between two storage endpoints goes through `earth.terrarium.common_storage_lib.storage.util.TransferUtil` rather than hand-rolled loops: `move`, `moveAny`, `moveFiltered` (predicate-based — the exact primitive M2's filters use), `moveAll`, `moveValue` (pressure-tank equalization, M5), `insertSlots`/`extractSlots`, `byItemTag`/`byIngredient` (ready-made `Predicate<ItemResource>` factories for filter modules).
- Code that moves resources between arbitrary endpoints (pipes, gantry, pattern providers) should be written against `CommonStorage`/`StorageIO` — the *interface* — not against `ArchieItemStorage` directly, since the other end of a transfer may be any third-party mod's storage.

## GUI convention

Every block with a screen follows the same chain: `TileRegistry` entry → `GuiRegistry` `MenuType` via `MenuRegistry.ofExtended` → a `ComposeBlockContainerMenu<T, SELF>` subclass implementing `registerSlotHandlers()` (binding `handler("group_name", tile.someArchieItemStorage)`) → a paired `ComposeContainerScreen` registered in `GuiRegistry.initClient()`.

Any field that needs to reach the client (progress bars, filter state, search results, job status) is declared `@Sync` on the `NBTBlockEntity` and read client-side via `observeProperty("fieldName", default)` inside a composable — writes auto-push a packet through Archie's `BlockEntityStateManager`, no manual networking required. This is used for essentially all GUI state throughout every milestone. Bespoke `BoilerplateNetworkChannel` packets are reserved for things that are genuinely *actions*, not block-entity state: crafting request submission, warehouse withdraw request, mid-flight pipe-item/gantry position sync.

Theming: ship theme JSONs under `assets/boilerplate/archie_themes/<type>/<composable>.json` (Archie's reload listeners scan all namespaces automatically — no per-mod registration needed) and wrap composable trees in `Theme(namespace = "boilerplate") { }`.

## Dev-only code (datagen / gametest)

No separate Gradle subprojects (see `../../AGENTS.md`). `datagen`/`gametest` packages live inside `common`, invoked from `Boilerplate.initCommon()` gated behind `Platform.isDevelopmentEnvironment()`/Archie's `AGameTestPlatform`. Gametests should validate at minimum: pipe network merge/split correctness (M1), gantry pick/place round-trips (M3), and crafting DAG resolution against cyclic/impossible patterns (M4).

## Cross-cutting: the `PressureConsumer` hook

Pressure is a gate first, a speed bonus on top — **no pressure reachable means no operation at all**, but once a machine has *enough* to run, more available pressure makes it run faster still. This is the mechanic that lets a pressure-infrastructure investment (bigger compressors, more tanks, a fatter pressure network) let production scale into the late game, rather than every machine being capped at a fixed rate regardless of supply, while still keeping pneumatics from running on nothing the way a redstone-powered machine effectively can (see `m5-pressure-power.md`'s "Core mechanic" section).

Even though pressure isn't wired up until M5, every "active" tick operation introduced from M1 onward (extractor pipe firing, gantry job execution, assembly-table craft) should implement a no-op version of this hook from day one:

```kotlin
interface PressureConsumer {
    /** Pressure/tick this operation requires to run at all, and draws at 1.0x (baseline) speed. */
    val basePressureCost: Long get() = 0

    /** Pressure/tick this operation can usefully draw at its fastest — caps how much speed extra supply can buy. */
    val maxPressureDraw: Long get() = basePressureCost

    /**
     * Called once per active tick with the local pressure line/tank. Draws up to [maxPressureDraw].
     * Returns `0.0` — a hard gate, not a divisor — when [line] can't even cover [basePressureCost];
     * every caller has to check for that and skip its own operation entirely rather than run it at
     * some reduced rate. Otherwise returns the resulting speed multiplier (drawn / basePressureCost),
     * at least `1.0`. `1.0` with no pressure hooked up at all (basePressureCost = 0 by default).
     */
    fun onPressureTick(line: ArchieEnergyStorage): Double = 1.0
}
```

M1–M4 implementations keep `basePressureCost`/`maxPressureDraw` at the default `0` (so `onPressureTick` always returns `1.0` and everything runs at the current fixed speed) and just make sure their tick loop multiplies its base duration/interval by whatever `onPressureTick` returns *after* checking for the `0.0` gate, so M5 only has to (a) give real machines nonzero costs and (b) hook them into a real pressure network — not touch every tick loop's structure. Exact cost/multiplier curve numbers are playtesting, not architecture.

Every `PipeHookType` (M1/M2/M3 hook kinds) also requires pressure to operate, but doesn't go through this interface at all: a segment can carry several independent hooks needing their own separate gate checks, not one aggregate multiplier for the whole segment, so `MultipartBlockEntity` draws and gates each hook's own cost directly instead — see `m5-pressure-power.md`'s "Hooks: gated the same way, without the speed-scaling layer" section.
