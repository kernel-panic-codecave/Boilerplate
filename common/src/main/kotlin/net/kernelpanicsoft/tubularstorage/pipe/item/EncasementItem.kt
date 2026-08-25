package net.kernelpanicsoft.tubularstorage.pipe.item

import net.kernelpanicsoft.tubularstorage.pipe.block.MultipartBlock
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock.Companion.propertiesByDirection
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.EncasementTypeRegistry
import net.minecraft.Util
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Block

/**
 * Places a fresh [BlockRegistry.Multipart] wrapped in [encasementId]'s own encasement - the whole-segment
 * counterpart to [HookItem], which this mirrors step for step. Right-clicking an existing pipe/hook
 * segment instead attaches directly (see [PipeBlock.useItemOn]/[MultipartBlock.useItemOn]) without ever
 * reaching [place].
 */
class EncasementItem(properties: Properties, val encasementId: ResourceLocation) : BlockItem(BlockRegistry.Multipart, properties) {

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
		if (tile.encasement.value != null) return result
		val encasementType = EncasementTypeRegistry.byId(encasementId) ?: return result

		tile.encasement.value = encasementType.createState()
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL)
		return result
	}

	override fun getDescriptionId(): String? = Util.makeDescriptionId("encasement", encasementId)
}
