package net.kernelpanicsoft.tubularstorage.pipe.block

import com.mojang.serialization.MapCodec
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * A pipe segment rendered translucent, whose traveling items are visible in transit - see
 * [net.kernelpanicsoft.tubularstorage.pipe.client.TravelingItemBlockEntityRenderer]. Mechanically
 * identical to a plain [PipeBlock] (same connections/shape/network participation); only its own
 * [TileRegistry.GlassPipe] block entity type and blockstate/models differ, so that renderer never
 * runs for the far more common opaque tier.
 */
class GlassPipeBlock(properties: Properties) : PipeBlock(properties) {

	override val showsTravelingItems: Boolean = true
	override val isTranslucent: Boolean = true

	override fun codec(): MapCodec<out PipeBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? = TileRegistry.GlassPipe.create(pos, state)

	override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
		createTickerHelper(type, TileRegistry.GlassPipe, PipeBlockEntity::tick)

	companion object {
		val CODEC: MapCodec<GlassPipeBlock> = simpleCodec(::GlassPipeBlock)
	}
}
