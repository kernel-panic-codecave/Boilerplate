package net.kernelpanicsoft.tubularstorage.power

import earth.terrarium.common_storage_lib.storage.base.ValueStorage
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType

/**
 * Fabric's own [PressureLookup] backing [PressureApi] - built directly on Fabric API's own
 * [BlockApiLookup], the exact same generic mechanism Team Reborn Energy's `EnergyStorage.SIDED` is
 * itself built on, just registered under Tubular Storage's own [PressureApi.ID] instead of theirs.
 */
object FabricPressureLookup : PressureLookup {
	private val LOOKUP: BlockApiLookup<ValueStorage, Direction?> =
		BlockApiLookup.get(PressureApi.ID, ValueStorage::class.java, Direction::class.java)

	override fun find(level: Level, pos: BlockPos, direction: Direction?): ValueStorage? = LOOKUP.find(level, pos, direction)

	override fun <T : BlockEntity> registerBlockEntity(type: BlockEntityType<T>, selector: (T, Direction?) -> ValueStorage?) {
		LOOKUP.registerForBlockEntity({ entity, direction -> selector(entity, direction) }, type)
	}
}
