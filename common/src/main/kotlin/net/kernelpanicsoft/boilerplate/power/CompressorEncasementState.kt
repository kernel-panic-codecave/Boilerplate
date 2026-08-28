package net.kernelpanicsoft.boilerplate.power

import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.pipe.encasement.EncasementHolderState
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity

/**
 * Burns a fuel item out of its own [fuel] slot to fill [pressure], then (via
 * [net.kernelpanicsoft.boilerplate.power.network.PressurePipeNetworkManager]'s own
 * equalization pass, the same one a tank participates in) pushes surplus outward across the
 * pressure network - see [CompressorEncasementType.tick] and `docs/design/m5-pressure-power.md`.
 */
class CompressorEncasementState : EncasementHolderState(CompressorEncasementType.ID), PressureStorageExposer {
	val pressure: ArchieEnergyStorage by energyField(CAPACITY)

	override fun exposedPressureStorage(tile: MultipartBlockEntity): ArchieEnergyStorage = pressure

	/** The one fuel slot this compressor burns from - furnace-analog. */
	val fuel: ArchieItemStorage by itemField(1)

	/** Ticks remaining on the fuel item currently burning, `0` if none is - furnace-analog to `AbstractFurnaceBlockEntity.litTime`. */
	var burnTicksRemaining: Int by field(Int.serializer()) { 0 }

	companion object {
		/** A single flat tier for now - exact balance numbers are explicitly deferred to playtesting, see `docs/design/m5-pressure-power.md`. */
		const val CAPACITY = 4_000L
	}
}
