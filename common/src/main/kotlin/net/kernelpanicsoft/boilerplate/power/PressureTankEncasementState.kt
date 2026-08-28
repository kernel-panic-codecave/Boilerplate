package net.kernelpanicsoft.boilerplate.power

import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage
import net.kernelpanicsoft.boilerplate.pipe.encasement.EncasementHolderState
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity

/**
 * Holds a fixed-capacity [pressure] buffer - one endpoint on the pressure network, equalized
 * against every other tank/compressor on the same
 * [net.kernelpanicsoft.boilerplate.power.network.PressurePipeNetwork] by
 * [net.kernelpanicsoft.boilerplate.power.network.PressurePipeNetworkManager]'s own per-tick
 * equalization pass - see `docs/design/m5-pressure-power.md`. Purely passive: not itself a
 * [PressureConsumer], since a tank isn't an active tick operation.
 */
class PressureTankEncasementState : EncasementHolderState(PressureTankEncasementType.ID), PressureStorageExposer {
	val pressure: ArchieEnergyStorage by energyField(CAPACITY)

	override fun exposedPressureStorage(tile: MultipartBlockEntity): ArchieEnergyStorage = pressure

	companion object {
		/** A single flat tier for now - exact balance numbers are explicitly deferred to playtesting, see `docs/design/m5-pressure-power.md`. */
		const val CAPACITY = 10_000L
	}
}
