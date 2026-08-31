package net.kernelpanicsoft.boilerplate.pipe.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Inventory

/** Menu for the sorting hook attached to [tile]'s [direction] face: its filter grid and mode/priority/color config. */
class SortingHookMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<MultipartBlockEntity, SortingHookMenu>(GuiRegistry.SortingHook, id, inventory, tile) {

	/** [tile]'s own position - exposed since [tile] itself is `protected`, for the C2S routing-edit/ghost-slot packets. */
	val pos: BlockPos get() = tile.blockPos

	/** [direction]'s current [RoutingModule], read once when the screen opens - see `docs/design/m2-sorting-routing.md`. */
	fun currentRouting(): RoutingModule = (tile.hooks[direction.name] as? SortingHookState)?.routing ?: RoutingModule()

	/**
	 * A real, vanilla-[net.minecraft.world.inventory.Slot]-backed filter-card slot, exactly like
	 * [net.kernelpanicsoft.boilerplate.warehouse.rack.AbstractRackMenu]'s own - a card here is
	 * genuinely taken from (and returnable to) the player's inventory rather than referenced in
	 * place.
	 */
	override fun registerSlotHandlers() {
		val state = tile.hooks[direction.name] as? SortingHookState ?: return
		handler("filter", state.filter) { it.item is FilterCardItem }
	}
}
