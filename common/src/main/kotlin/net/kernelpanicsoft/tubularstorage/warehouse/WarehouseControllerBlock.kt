package net.kernelpanicsoft.tubularstorage.warehouse

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/** A warehouse's single binding point - see [WarehouseWandItem]/[WarehouseControllerBlockEntity]. */
class WarehouseControllerBlock(properties: Properties) : BaseEntityBlock(properties) {
	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WarehouseControllerBlockEntity(pos, state)

	companion object {
		val CODEC: MapCodec<WarehouseControllerBlock> = simpleCodec(::WarehouseControllerBlock)
	}
}
