package net.kernelpanicsoft.tubularstorage.warehouse

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.NBTHolder
import net.kernelpanicsoft.archie.serialization.field
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext

/**
 * Binds a [WarehouseControllerBlockEntity]'s [Bounds] volume - see
 * `docs/design/m3-warehouse-storage.md` (decision #2: no literal built shell, just a wand-defined
 * region). The first two right-clicks record corners on the wand itself, via its own
 * [NBTHolder.item] state rather than any block entity, so the pending selection survives between
 * clicks and travels with the stack. A third right-click, against the controller to bind, commits
 * [Bounds.of] those two corners to it and clears the wand's selection. Right-clicking anything
 * else while both corners are already set restarts the selection from that click instead of
 * getting stuck waiting for a controller.
 */
class WarehouseWandItem(properties: Properties) : Item(properties) {
	override fun useOn(context: UseOnContext): InteractionResult {
		val level = context.level
		val pos = context.clickedPos
		val stack = context.itemInHand
		if (level.isClientSide) return InteractionResult.SUCCESS

		val holder = NBTHolder.item(stack)
		var selection: Selection by holder.field { Selection() }
		val first = selection.first
		val second = selection.second

		val controller = level.getBlockEntity(pos) as? WarehouseControllerBlockEntity
		if (controller != null && first != null && second != null) {
			controller.bounds = Bounds.of(first, second)
			selection = Selection()
			level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1f, 1f)
			return InteractionResult.SUCCESS
		}

		selection = when {
			first == null -> Selection(first = pos)
			second == null -> Selection(first = first, second = pos)
			else -> Selection(first = pos)
		}
		level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 1f)
		return InteractionResult.SUCCESS
	}
}

/** The wand's own pending corner selection, persisted on the [net.minecraft.world.item.ItemStack] itself between clicks. */
@Serializable
private data class Selection(val first: SBlockPos? = null, val second: SBlockPos? = null)
