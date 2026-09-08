package net.kernelpanicsoft.boilerplate.warehouse.rack

import com.mojang.serialization.MapCodec
import net.kernelpanicsoft.boilerplate.util.voxelShape
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.VoxelShape

/** See [GeneralRackBlockEntity]. */
class GeneralRackBlock(properties: Properties) : RackBlock<GeneralRackBlockEntity>(properties) {
	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = GeneralRackBlockEntity(pos, state)

	override val shape: VoxelShape = voxelShape {
		box(0.0, 0.0, 0.0, 0.125, 1.0, 0.125)
		box(0.875, 0.0, 0.0, 1.0, 1.0, 0.125)
		box(0.0, 0.0, 0.875, 0.125, 1.0, 1.0)
		box(0.875, 0.0, 0.875, 1.0, 1.0, 1.0)
		box(0.125, 0.1875, 0.0, 0.875, 0.25, 1.0)
		box(0.125, 0.5, 0.0, 0.875, 0.5625, 1.0)
		box(0.125, 0.8125, 0.0, 0.875, 0.875, 1.0)
		box(0.1875, 0.25, 0.1875, 0.4375, 0.4375, 0.4375)
		box(0.5625, 0.5625, 0.1875, 0.8125, 0.75, 0.4375)
		box(0.1875, 0.875, 0.5625, 0.4375, 1.0, 0.8125)
	}

	companion object {
		val CODEC: MapCodec<GeneralRackBlock> = simpleCodec(::GeneralRackBlock)
	}
}
