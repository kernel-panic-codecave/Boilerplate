package net.kernelpanicsoft.boilerplate.power

import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage

/**
 * Something whose active tick operation requires pressure to run at all - pneumatic machinery, not
 * electric: no supply reachable means no operation this tick, full stop - but more pressure than
 * the bare minimum makes it run faster, not just "on". A zero-cost consumer ([basePressureCost]
 * `<= 0`, the M1-M4 default for anything that hasn't been given a real requirement yet) draws
 * nothing and always runs at `1.0`x, unaffected either way - see [PressureLine.find] for how a real
 * [net.kernelpanicsoft.boilerplate.power.network.PressurePipeNetwork]-connected line is resolved.
 */
interface PressureConsumer {
	/** Pressure/tick this operation requires to run at all, at 1.0x (baseline) speed. */
	val basePressureCost: Long get() = 0

	/** Pressure/tick this operation can usefully draw at its fastest - caps how much speed extra supply can buy. */
	val maxPressureDraw: Long get() = basePressureCost

	/**
	 * Draws up to [maxPressureDraw] from [line] this tick and returns the resulting speed
	 * multiplier - `0.0` (a hard gate, not a floor) if [line] can't even cover [basePressureCost]:
	 * a caller must skip its own operation entirely for this tick rather than treat `0.0` as a
	 * divisor. Otherwise at least `1.0`, scaling proportionally higher toward [maxPressureDraw] the
	 * more is actually available to draw - pressure is a requirement first, a speed bonus on top of
	 * that. A zero-cost consumer draws nothing and always returns `1.0`, unaffected either way.
	 */
	fun onPressureTick(line: ArchieEnergyStorage): Double {
		if (basePressureCost <= 0) return 1.0
		val available = line.extract(maxPressureDraw, true)
		if (available < basePressureCost) return 0.0
		val drawn = line.extract(available, false)
		return (drawn.toDouble() / basePressureCost).coerceAtLeast(1.0)
	}
}
