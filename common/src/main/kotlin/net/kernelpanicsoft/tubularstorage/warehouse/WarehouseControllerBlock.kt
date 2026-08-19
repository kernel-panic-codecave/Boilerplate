package net.kernelpanicsoft.tubularstorage.warehouse

import com.mojang.serialization.MapCodec
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/** A warehouse's single binding point - see [WarehouseWandItem]/[WarehouseControllerBlockEntity]. */
class WarehouseControllerBlock(properties: Properties) : BaseEntityBlock(properties) {
	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WarehouseControllerBlockEntity(pos, state)

	override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
		createTickerHelper(type, TileRegistry.WarehouseController, WarehouseControllerBlockEntity::tick)

	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

	/**
	 * Clears [WarehouseControllerBlockEntity.bounds] (which also removes the placed rail frame,
	 * via that property's own setter) on a genuine removal - `!state.is(newState.block)` excludes a
	 * same-block state change, e.g. a neighbor update, from counting as one. Deliberately *not*
	 * done from [WarehouseControllerBlockEntity.setRemoved] instead, which also fires on an
	 * ordinary chunk unload (including the whole world unloading at shutdown), not just a real
	 * removal - see [WarehouseControllerBlockEntity.bounds]'s own KDoc for the deadlock that caused.
	 */
	override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
		if (!state.`is`(newState.block) && !level.isClientSide) {
			(level.getBlockEntity(pos) as? WarehouseControllerBlockEntity)?.bounds = null
		}
		super.onRemove(state, level, pos, newState, movedByPiston)
	}

	companion object {
		val CODEC: MapCodec<WarehouseControllerBlock> = simpleCodec(::WarehouseControllerBlock)
	}
}
