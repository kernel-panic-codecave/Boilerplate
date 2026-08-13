package net.kernelpanicsoft.tubularstorage.pipe.block

import com.mojang.serialization.MapCodec
import net.kernelpanicsoft.tubularstorage.pipe.entity.ExtractorPipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/** A [PipeBlock] that also periodically pulls from an adjacent inventory - see [ExtractorPipeBlockEntity]. */
class ExtractorPipeBlock(properties: Properties) : PipeBlock(properties) {
	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? = TileRegistry.ExtractorPipe.create(pos, state)

	override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
		createTickerHelper(type, TileRegistry.ExtractorPipe, PipeBlockEntity::tick)

	companion object {
		val CODEC: MapCodec<ExtractorPipeBlock> = simpleCodec(::ExtractorPipeBlock)
	}
}
