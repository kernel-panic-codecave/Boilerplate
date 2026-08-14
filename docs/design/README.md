# Tubular Storage — Design Docs

Implementation-level design for `ROADMAP.md`'s milestones. Each milestone gets its own file; this one covers the foundations shared across all of them so the per-milestone docs aren't repeating themselves.

- [M1 — Pipe Network Core](m1-pipe-network.md)
- [M2 — Sorting & Routing](m2-sorting-routing.md)
- [M3 — Warehouse Storage](m3-warehouse-storage.md)
- [M4 — Crafting Automation](m4-crafting-automation.md)
- [M5 — Steampunk Power Layer](m5-pressure-power.md)
- [M6 — Polish & Parity](m6-polish-parity.md)

All of this is grounded in Archie's and `earth.terrarium.common_storage_lib`'s (CSL) real, verified APIs — no invented classes or method names. This is the spec each milestone gets built against; M1/M2 are implemented, M3 is in progress, M4+ aren't started yet.

## Package layout

Under `common/src/main/kotlin/net/kernelpanicsoft/tubularstorage/`:

```
registry/    BlockRegistry, ItemRegistry, TileRegistry, GuiRegistry, SoundRegistry, ParticleRegistry
config/      Config.kt
network/     TubularStorageNetworkChannel + packet data classes
pipe/        network/ (graph+routing), block/, entity/
warehouse/   controller, index, gantry, terminal
crafting/    pattern, resolver, provider/assembly blocks
power/       pressure network, tank, compressor
client/      Compose screens/renderers
datagen/     gated via Platform.isDevelopmentEnvironment()
gametest/    gated via AGameTestPlatform
```

## Resolved architectural decisions

These came up during design as genuine forks and were decided with the user — treat them as settled, not open:

1. **Pipe upgrades (M1/M2)**: an attachable module item unlocks sorting/filtering/color behavior on a plain pipe. Not separate pipe-per-behavior blocks.
2. **Warehouse bounds (M3)**: a `WarehouseWandItem` defines the storage volume by right-clicking two corners, then binding to the controller. No literal built shell required.
3. **Gantry rails (M3)**: the crane moves within the wand-defined volume with no physical rail blocks, no collision, no construction cost — but the client renders a **ghost/translucent rail overlay** along its travel path for visual readability. Purely a rendering feature layered onto the wand-defined envelope.
4. **Crafting execution (M4)**: a physical Assembly Table processes crafts over time, rather than resolving instantly once inputs are available. This is what M5's pressure mechanic gates.

Deferred and *not* blocking any of this: exact balance numbers (extraction interval, gantry speed, pressure costs — playtesting), whether over-pressure should be dangerous (M5, optional stretch), and exact block names/textures/art.

## Gotcha: Archie's `listField`/`mapField`/`field` only persist through structural mutation

`NBTHolderImpl`'s property getter for `by listField(...)` (and `mapField`/`field`) **decodes a fresh copy from storage on every single access** — it does not hand back a stable reference. Persistence happens only through `ObservableList`'s intercepted structural operations (`add`, `removeAt`, `set`, `clear`, ...), each of which re-encodes the whole collection back into storage. Two patterns silently do nothing as a result:

- Mutating a `var` field on an element already inside the list (e.g. `item.progress += x`) — not a structural operation, so `ObservableList` never sees it. The mutation lands on a throwaway copy; the next access re-decodes from the unchanged backing storage as if it never happened.
- Removing via an `Iterator` (`iterator.remove()`) — `ObservableList` doesn't override `iterator()`, so removal isn't observed either.

Correct pattern: fetch the property **exactly once** per use (into a local `val`), and touch it only through the intercepted operations — index-based `list[i] = element.copy(...)` and `list.removeAt(i)`, never in-place field mutation or iterator removal. This bit M1's `PipeBlockEntity` tick loop for real (`TravelingItem.progress` silently never advanced) before being caught and fixed — see [m1-pipe-network.md](m1-pipe-network.md).

## Registries

One `ADeferredRegistryHolder<T>` singleton per registrable type, mirroring Archie's own `test/` example mod exactly:

```kotlin
object BlockRegistry : ADeferredRegistryHolder<Block>(TubularStorage.MOD, Registries.BLOCK) {
    val SomePipe by register("some_pipe") { PipeBlock(blockProperties(Blocks.IRON_BLOCK) { }) }
}
object ItemRegistry : ADeferredRegistryHolder<Item>(TubularStorage.MOD, Registries.ITEM) { /* ... */ }
object TileRegistry : ADeferredRegistryHolder<BlockEntityType<*>>(TubularStorage.MOD, Registries.BLOCK_ENTITY_TYPE) { /* ... */ }
object GuiRegistry : ADeferredRegistryHolder<MenuType<*>>(TubularStorage.MOD, Registries.MENU) {
    override fun initClient() { MenuRegistry.registerScreenFactory(SomeMenu, ::SomeScreen) }
}
```

Later: `SoundRegistry`, `ParticleRegistry` (both `ADeferredRegistryHolder`, same pattern). All `.init()` calls happen from `TubularStorage.init()`; `GuiRegistry.initClient()` (and any other registry needing client-side registration) is called from `TubularStorage.initClient()`.

## CSL storage adapter pattern

Used identically by every milestone that touches item/fluid/pressure storage — don't deviate from this per-milestone:

- **Reading/writing a neighboring block** (vanilla chest, another mod's machine, another Tubular Storage machine) always goes through CSL's lookup:
  ```kotlin
  val storage: CommonStorage<ItemResource>? = ItemApi.BLOCK.find(level, pos, state, blockEntity, direction)
  ```
  This is what makes pipes/gantry/patterns interoperate with vanilla and third-party inventories for free — CSL's Fabric backend bridges to `ItemStorage.SIDED` (Fabric Transfer API), its NeoForge backend bridges to `Capabilities.ItemHandler.BLOCK` (`IItemHandler`), both transparently.
- **A Tubular Storage block's own buffer** is always declared via `NBTHolder.itemField(size)` — this yields an `ArchieItemStorage`, which is already an NBT-persisted `CommonStorage<ItemResource>` (Archie ships this, no need to hand-roll a `CommonStorage` implementation for our own machines). The pressure/energy analogue is `NBTHolder.energyField(capacity)` → `ArchieEnergyStorage : ValueStorage`. Both are exposed outward *once*, at block-entity-type registration time:
  ```kotlin
  TileRegistry.SomeTile.exposeItemStorage { tile -> tile.buffer }       // ArchieCapabilityExposure
  TileRegistry.PressureTank.exposeEnergyStorage { tile -> tile.pressure }
  ```
- **All movement** between two storage endpoints goes through `earth.terrarium.common_storage_lib.storage.util.TransferUtil` rather than hand-rolled loops: `move`, `moveAny`, `moveFiltered` (predicate-based — the exact primitive M2's filters use), `moveAll`, `moveValue` (pressure-tank equalization, M5), `insertSlots`/`extractSlots`, `byItemTag`/`byIngredient` (ready-made `Predicate<ItemResource>` factories for filter modules).
- Code that moves resources between arbitrary endpoints (pipes, gantry, pattern providers) should be written against `CommonStorage`/`StorageIO` — the *interface* — not against `ArchieItemStorage` directly, since the other end of a transfer may be any third-party mod's storage.

## GUI convention

Every block with a screen follows the same chain: `TileRegistry` entry → `GuiRegistry` `MenuType` via `MenuRegistry.ofExtended` → a `ComposeBlockContainerMenu<T, SELF>` subclass implementing `registerSlotHandlers()` (binding `handler("group_name", tile.someArchieItemStorage)`) → a paired `ComposeContainerScreen` registered in `GuiRegistry.initClient()`.

Any field that needs to reach the client (progress bars, filter state, search results, job status) is declared `@Sync` on the `NBTBlockEntity` and read client-side via `observeProperty("fieldName", default)` inside a composable — writes auto-push a packet through Archie's `BlockEntityStateManager`, no manual networking required. This is used for essentially all GUI state throughout every milestone. Bespoke `TubularStorageNetworkChannel` packets are reserved for things that are genuinely *actions*, not block-entity state: crafting request submission, warehouse withdraw request, mid-flight pipe-item/gantry position sync.

Theming: ship theme JSONs under `assets/tubularstorage/archie_themes/<type>/<composable>.json` (Archie's reload listeners scan all namespaces automatically — no per-mod registration needed) and wrap composable trees in `Theme(namespace = "tubularstorage") { }`.

## Dev-only code (datagen / gametest)

No separate Gradle subprojects (see `CLAUDE.md`). `datagen`/`gametest` packages live inside `common`, invoked from `TubularStorage.initCommon()` gated behind `Platform.isDevelopmentEnvironment()`/Archie's `AGameTestPlatform`. Gametests should validate at minimum: pipe network merge/split correctness (M1), gantry pick/place round-trips (M3), and crafting DAG resolution against cyclic/impossible patterns (M4).

## Cross-cutting: the `PressureConsumer` hook

Pressure is not a pass/fail gate — **more available pressure makes the operation faster**, not just "allowed vs. stalled". This is the mechanic that lets a pressure-infrastructure investment (bigger compressors, more tanks, a fatter pressure network) let production scale into the late game, rather than every machine being capped at a fixed rate regardless of supply.

Even though pressure isn't wired up until M5, every "active" tick operation introduced from M1 onward (extractor pipe firing, gantry job execution, assembly-table craft) should implement a no-op version of this hook from day one:

```kotlin
interface PressureConsumer {
    /** Pressure/tick this operation draws at 1.0x (baseline) speed. */
    val basePressureCost: Long get() = 0

    /** Pressure/tick this operation can usefully draw at its fastest — caps how much speed extra supply can buy. */
    val maxPressureDraw: Long get() = basePressureCost

    /**
     * Called once per active tick with the local pressure line/tank. Draws up to [maxPressureDraw],
     * returns the resulting speed multiplier (drawn / basePressureCost, clamped to at least a small
     * minimum so machines don't fully halt on brief shortfalls — exact floor/ceiling left to
     * playtesting). 1.0 = baseline speed with no pressure hooked up (basePressureCost = 0 by default).
     */
    fun onPressureTick(line: ArchieEnergyStorage): Double = 1.0
}
```

M1–M4 implementations keep `basePressureCost`/`maxPressureDraw` at the default `0` (so `onPressureTick` is never meaningfully called and everything runs at the current fixed speed) and just make sure their tick loop multiplies its base duration/interval by whatever `onPressureTick` returns, so M5 only has to (a) give real machines nonzero costs and (b) hook them into a real pressure network — not touch every tick loop's structure. Exact cost/multiplier curve numbers are playtesting, not architecture.
