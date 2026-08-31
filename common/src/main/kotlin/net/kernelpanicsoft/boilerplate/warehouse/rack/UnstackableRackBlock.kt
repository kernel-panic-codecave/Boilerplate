package net.kernelpanicsoft.boilerplate.warehouse.rack

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/** See [UnstackableRackBlockEntity]. */
class UnstackableRackBlock(properties: Properties) : RackBlock<UnstackableRackBlockEntity>(properties) {
	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = UnstackableRackBlockEntity(pos, state)

	companion object {
		val CODEC: MapCodec<UnstackableRackBlock> = simpleCodec(::UnstackableRackBlock)
	}
}
