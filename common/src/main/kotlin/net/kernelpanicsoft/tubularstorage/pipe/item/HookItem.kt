package net.kernelpanicsoft.tubularstorage.pipe.item

import net.kernelpanicsoft.tubularstorage.pipe.block.HookBlock
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock.Companion.propertiesByDirection
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookState
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Block

/**
 * Places a fresh [BlockRegistry.Hook] with [hookId] attached to the face touching whatever was
 * clicked - see `docs/design/m1-pipe-network.md`. Right-clicking an existing pipe/hook face
 * instead attaches directly (see [net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock.useItemOn]/
 * [net.kernelpanicsoft.tubularstorage.pipe.block.HookBlock.useItemOn]) without ever reaching [place].
 */
class HookItem(properties: Properties, val hookId: ResourceLocation) : BlockItem(BlockRegistry.Hook, properties) {

	override fun place(context: BlockPlaceContext): InteractionResult {
		val result = super.place(context)
		if (!result.consumesAction() && result != InteractionResult.FAIL) return result
		val level = context.level
		if (level.isClientSide) return result

		val pos = context.clickedPos
		val state = level.getBlockState(pos)
		if (result == InteractionResult.FAIL && state.block.let { it is PipeBlock && it !is HookBlock })
		{
			val oldTile = level.getBlockEntity(pos) as? PipeBlockEntity ?: return InteractionResult.PASS

			val tag = CompoundTag()
			oldTile.saveToTag(tag)

			val hookState = propertiesByDirection.values.fold(BlockRegistry.Hook.defaultBlockState()) { result, property ->
				result.setValue(property, state.getValue(property))
			}
			level.setBlock(pos, hookState, Block.UPDATE_CLIENTS)
			level.playSound(null, pos, state.soundType.placeSound, SoundSource.BLOCKS, 1f, 1f)
			val newTile = level.getBlockEntity(pos) as? HookBlockEntity ?: return InteractionResult.SUCCESS
			newTile.loadFromTag(tag)
			newTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(state.block)
		}
		val tile = level.getBlockEntity(pos) as? HookBlockEntity ?: return result
		val direction = context.clickedFace.opposite
		if (tile.hooks.containsKey(direction.name)) return result

		tile.hooks[direction.name] = HookState(type = hookId)
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
		return result
	}
}
