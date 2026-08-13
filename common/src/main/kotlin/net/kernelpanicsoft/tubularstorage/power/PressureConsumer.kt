package net.kernelpanicsoft.tubularstorage.power

import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage

/**
 * Something whose active tick operation runs faster with more available pressure, rather than
 * being simply allowed or denied. Implemented as a no-op by every M1-M4 tick operation until M5
 * gives real machines nonzero costs and wires them to a real pressure network.
 */
interface PressureConsumer {
	/** Pressure/tick this operation draws at 1.0x (baseline) speed. */
	val basePressureCost: Long get() = 0

	/** Pressure/tick this operation can usefully draw at its fastest - caps how much speed extra supply can buy. */
	val maxPressureDraw: Long get() = basePressureCost

	/** Called once per active tick with the local pressure line/tank; returns the resulting speed multiplier. */
	fun onPressureTick(line: ArchieEnergyStorage): Double = 1.0
}
