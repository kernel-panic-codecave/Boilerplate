package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import dev.architectury.registry.menu.MenuRegistry
import kotlinx.serialization.ExperimentalSerializationApi
import net.kernelpanicsoft.archie.serialization.SerializationManager
import net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardMenu
import net.minecraft.Util
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * A configurable condition for a [net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState]
 * ghost filter grid - one item per registered [FilterConditionType], its own [conditionTypeId]
 * fixed for the item's whole lifetime (mirroring
 * [net.kernelpanicsoft.boilerplate.pipe.item.HookItem]'s own `hookId` - a hook's attached kind
 * is likewise fixed by which item placed it, not switchable afterward). See
 * [FilterCardState]/`docs/design/m2-sorting-routing.md`. Right-clicking one held in hand opens
 * [FilterCardMenu] against that hotbar slot ([FilterCardTarget.PlayerSlot]); one already dropped
 * into a ghost slot is instead middle-clicked open, from whichever screen shows that grid, via
 * [FilterCardTarget.HookFilterSlot]/[FilterCardTarget.ChildSlot].
 */
class FilterCardItem(properties: Properties, val conditionTypeId: ResourceLocation) : Item(properties) {
	override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
		val stack = player.getItemInHand(hand)
		if (level.isClientSide || hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(stack)

		openFilterCardMenu(player as ServerPlayer, FilterCardTarget.PlayerSlot(player.inventory.selected))
		return InteractionResultHolder.success(stack)
	}

	override fun getDescriptionId(): String = Util.makeDescriptionId("filter", conditionTypeId)

	companion object {
		/** Opens [FilterCardMenu] against [target] - the single entry point both [use] and a ghost slot's own middle-click handling go through. */
		@OptIn(ExperimentalSerializationApi::class)
		fun openFilterCardMenu(player: ServerPlayer, target: FilterCardTarget) {
			MenuRegistry.openExtendedMenu(
				player,
				SimpleMenuProvider(
					{ id, inventory, _ -> FilterCardMenu(id, inventory, target) },
					Component.translatable(target.resolve(player.level(), player).getStack().descriptionId),
				),
			) { buf -> buf.writeByteArray(SerializationManager.cbor.encodeToByteArray(FilterCardTarget.serializer(), target)) }
		}
	}
}
