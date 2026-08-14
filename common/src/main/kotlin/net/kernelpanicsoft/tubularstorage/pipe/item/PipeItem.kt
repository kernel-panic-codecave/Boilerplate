package net.kernelpanicsoft.tubularstorage.pipe.item

import net.kernelpanicsoft.tubularstorage.pipe.block.HookBlock
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Block

class PipeItem(pipe: PipeBlock, properties: Properties) : BlockItem(pipe, properties)
{
	override fun place(context: BlockPlaceContext): InteractionResult?
	{
		val result = super.place(context)
		if (!result.consumesAction() && result != InteractionResult.FAIL) return result
		val level = context.level
		if (level.isClientSide) return result
		val pos = context.clickedPos
		val state = level.getBlockState(pos)
		if (result == InteractionResult.FAIL && state.block.let { it is HookBlock })
		{
			val tile = level.getBlockEntity(pos) as? HookBlockEntity ?: return InteractionResult.PASS
			tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(block)
			level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL)
			state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL)
			level.playSound(null, pos, state.soundType.placeSound, SoundSource.BLOCKS, 1f, 1f)
			return InteractionResult.SUCCESS
		}
		return result
	}
}