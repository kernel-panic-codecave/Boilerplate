package net.kernelpanicsoft.boilerplate.warehouse.rack

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * The three shared-pool racks' blocks - see [PooledRackBlockEntity] for what they do.
 *
 * A full cube rather than the shelving silhouette [UnstackableRackBlock] cuts out of one: these hold
 * their capacity as one undivided pool, and a model with visible shelves would say the opposite.
 */
class DistributedMultiTankBlock(properties: Properties) : PooledRackBlock<DistributedMultiTankBlockEntity>(properties) {
	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = DistributedMultiTankBlockEntity(pos, state)

	companion object {
		val CODEC: MapCodec<DistributedMultiTankBlock> = simpleCodec(::DistributedMultiTankBlock)
	}
}

/** See [PooledRackBlockEntity]. */
class DistributedMultiBufferBlock(properties: Properties) : PooledRackBlock<DistributedMultiBufferBlockEntity>(properties) {
	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = DistributedMultiBufferBlockEntity(pos, state)

	companion object {
		val CODEC: MapCodec<DistributedMultiBufferBlock> = simpleCodec(::DistributedMultiBufferBlock)
	}
}

/** See [PooledRackBlockEntity]. */
class OmnibufferBlock(properties: Properties) : PooledRackBlock<OmnibufferBlockEntity>(properties) {
	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = OmnibufferBlockEntity(pos, state)

	companion object {
		val CODEC: MapCodec<OmnibufferBlock> = simpleCodec(::OmnibufferBlock)
	}
}

/** The shape and interaction the three share - a plain cube, opening the same filter/priority screen every rack does. */
abstract class PooledRackBlock<T : PooledRackBlockEntity>(properties: Properties) : RackBlock<T>(properties) {
	override val shape: VoxelShape = Shapes.block()
}
