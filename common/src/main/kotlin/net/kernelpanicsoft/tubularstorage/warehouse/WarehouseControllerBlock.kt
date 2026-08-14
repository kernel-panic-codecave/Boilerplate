package net.kernelpanicsoft.tubularstorage.warehouse

import com.mojang.serialization.MapCodec
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
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

	companion object {
		val CODEC: MapCodec<WarehouseControllerBlock> = simpleCodec(::WarehouseControllerBlock)
	}
}
