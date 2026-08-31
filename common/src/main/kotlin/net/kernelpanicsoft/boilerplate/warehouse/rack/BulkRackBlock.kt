package net.kernelpanicsoft.boilerplate.warehouse.rack

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/** See [BulkRackBlockEntity]. */
class BulkRackBlock(properties: Properties) : RackBlock<BulkRackBlockEntity>(properties) {
	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = BulkRackBlockEntity(pos, state)

	companion object {
		val CODEC: MapCodec<BulkRackBlock> = simpleCodec(::BulkRackBlock)
	}
}
