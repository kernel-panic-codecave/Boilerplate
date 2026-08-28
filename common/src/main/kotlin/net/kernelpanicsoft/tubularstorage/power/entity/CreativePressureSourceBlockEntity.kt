package net.kernelpanicsoft.tubularstorage.power.entity

import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * Backs [net.kernelpanicsoft.tubularstorage.power.block.CreativePressureSourceBlock] - a creative-
 * tab-only, never-craftable block supplying effectively unlimited pressure to whatever
 * [net.kernelpanicsoft.tubularstorage.power.PressureApi]-aware neighbor touches it, for testing and
 * creative-mode building without wiring up a real fuel-burning
 * [net.kernelpanicsoft.tubularstorage.power.CompressorEncasementType]. [pressure] is topped back up
 * to [CAPACITY] every tick, so any amount drawn from it - a hook's own
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType.basePressureCost],
 * [net.kernelpanicsoft.tubularstorage.power.network.PressurePipeNetworkManager.equalize] leveling
 * it into real tanks on the same network - is replaced before the next tick rather than actually
 * depleting it.
 */
class CreativePressureSourceBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) : NBTBlockEntity(type, pos, state) {
	constructor(pos: BlockPos, state: BlockState) : this(TileRegistry.CreativePressureSource, pos, state)

	val pressure: ArchieEnergyStorage by energyField(CAPACITY)

	fun tick() {
		pressure.set(CAPACITY)
	}

	companion object {
		const val CAPACITY = 1_000_000_000L

		fun tick(level: Level, pos: BlockPos, state: BlockState, tile: CreativePressureSourceBlockEntity) = tile.tick()
	}
}
