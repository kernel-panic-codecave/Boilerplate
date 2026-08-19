package net.kernelpanicsoft.tubularstorage.warehouse.rack

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/** See [GeneralRackBlockEntity]. */
class GeneralRackBlock(properties: Properties) : RackBlock(properties) {
	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = GeneralRackBlockEntity(pos, state)

	companion object {
		val CODEC: MapCodec<GeneralRackBlock> = simpleCodec(::GeneralRackBlock)
	}
}
