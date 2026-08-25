package net.kernelpanicsoft.tubularstorage.pipe.item

import net.kernelpanicsoft.tubularstorage.pipe.block.MultipartBlock
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock.Companion.propertiesByDirection
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry
import net.minecraft.Util
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
 * Places a fresh [BlockRegistry.Multipart] with [hookId] attached to the face touching whatever was
 * clicked - see `docs/design/m1-pipe-network.md`. Right-clicking an existing pipe/hook face
 * instead attaches directly (see [net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock.useItemOn]/
 * [net.kernelpanicsoft.tubularstorage.pipe.block.MultipartBlock.useItemOn]) without ever reaching [place].
 */
class HookItem(properties: Properties, val hookId: ResourceLocation) : BlockItem(BlockRegistry.Multipart, properties) {

	override fun place(context: BlockPlaceContext): InteractionResult {
		var result = super.place(context)
		if (!result.consumesAction() && result != InteractionResult.FAIL) return result
		val level = context.level
		if (level.isClientSide) return result

		val pos = context.clickedPos
		val state = level.getBlockState(pos)
		if (result == InteractionResult.FAIL && state.block.let { it is PipeBlock && it !is MultipartBlock })
		{
			val oldTile = level.getBlockEntity(pos) as? PipeBlockEntity ?: return InteractionResult.PASS

			val tag = CompoundTag()
			oldTile.saveToTag(tag)

			val hookState = propertiesByDirection.values.fold(BlockRegistry.Multipart.defaultBlockState()) { result, property ->
				result.setValue(property, state.getValue(property))
			}
			level.setBlockAndUpdate(pos, hookState)
			level.playSound(null, pos, hookState.soundType.placeSound, SoundSource.BLOCKS, 1f, 1f)
			val newTile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return InteractionResult.SUCCESS
			newTile.loadFromTag(tag)
			newTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(state.block)
			hookState.updateNeighbourShapes(level, pos, Block.UPDATE_ALL)
			result = InteractionResult.SUCCESS
		}
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return result
		val direction = context.clickedFace.opposite
		if (tile.hooks.containsKey(direction.name)) return result
		val hookType = HookTypeRegistry.byId(hookId) ?: return result

		tile.hooks.getOrPut(direction.name) { hookType.createState() }
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL)
		return result
	}

	override fun getDescriptionId(): String? = Util.makeDescriptionId("hook", hookId)
}
