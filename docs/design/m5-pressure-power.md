# M5 — Steampunk Power Layer

See [README.md](README.md) for the `PressureConsumer` hook this milestone activates.

## Core mechanic: pressure scales speed, not just permission

**Higher available pressure makes operations run faster — it is not a binary "enough vs. stalled" gate.** This is the axis that lets the mod keep up in the late game: instead of every machine being hard-capped at a fixed throughput regardless of investment, building more compressors/tanks/pressure network capacity directly buys more speed. This is why `PressureConsumer.onPressureTick(line)` (defined in [README.md](README.md#cross-cutting-the-pressureconsumer-hook)) returns a continuous multiplier rather than a boolean:

```kotlin
interface PressureConsumer {
    val basePressureCost: Long get() = 0     // pressure/tick at 1.0x speed
    val maxPressureDraw: Long get() = basePressureCost   // caps how much speed extra supply can buy
    fun onPressureTick(line: ArchieEnergyStorage): Double = 1.0
}
```

A machine with `basePressureCost = 0` (the M1–M4 default) is unaffected by pressure and always runs at 1.0x — this milestone's job is giving real machines nonzero costs and a real network to draw from, not changing any tick-loop structure introduced earlier.

## Storage primitive

Reuses `ArchieEnergyStorage : ValueStorage` relabeled as pressure — no new storage primitive needed:

```kotlin
class PressureTankBlockEntity(...) : NBTBlockEntity(...), PressureConsumer {
    val pressure by nbt.energyField(capacity)
}
// registration:
TileRegistry.PressureTank.exposeEnergyStorage { it.pressure }
```

`CompressorBlockEntity` burns a fuel item (furnace-analog) to fill its own `ArchieEnergyStorage`, then pushes into adjacent tanks/pressure lines using `TransferUtil.moveValue` for equalization — the exact same CSL primitive item transfer uses (`TransferUtil.move`), just applied to `ValueStorage` instead of `CommonStorage<ItemResource>`.

## Pressure piping — a separate network from item pipes

`PressurePipeBlock` gets its **own** block/network (distinct brass/rivet aesthetic from item tubes), rather than overloading M1's item-pipe network. Reasoning: pressure transport is continuous equalization each tick (`moveValue`), not discrete travel-time item simulation — forcing both payload kinds through one generic network would strain the tick model on one side or the other.

The topology bookkeeping (union-find merge, chunked-BFS split rebuild) is identical in shape to M1's `PipeNetworkManager` — factor a shared `AbstractPipeNetwork` base for that part, with `ItemPipeNetwork`/`PressurePipeNetwork` as separate concrete tick implementations underneath it.

## Throughput gating in practice

- Extractor pipes (M1): `onPressureTick` scales the effective `extractionIntervalTicks`.
- Gantry job execution (M3): `onPressureTick` scales crane movement speed / job throughput.
- Assembly-table crafts (M4): `onPressureTick` scales craft completion time.

All three already call through the `PressureConsumer` hook from their own milestone — this milestone only needs to give them real `basePressureCost`/`maxPressureDraw` values and wire `onPressureTick` to a real `PressurePipeNetwork`-connected tank, not touch their tick-loop structure.

## Deferred to playtesting / not blocking

- Exact unit/cost balance and the multiplier curve's floor/ceiling — pure playtesting.
- Whether over-pressure should be dangerous (tank rupture/explosion, BuildCraft/IC2-boiler-style) — thematically fun, but real scope/safety-design surface. Optional stretch, not assumed.
- Whether pressure should be networked (current lean, per the vision's framing of pressure as *the* throughput gate) vs. a simpler per-machine local resource — not force-closed here.
