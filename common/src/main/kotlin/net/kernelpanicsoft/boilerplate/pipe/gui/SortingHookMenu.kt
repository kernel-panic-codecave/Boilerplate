package net.kernelpanicsoft.boilerplate.pipe.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.HookHolderState
import net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType
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

	/** The filter hook on this face, or `null` - only [net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookType] batches, though the menu is shared with the sync hook. */
	private fun filterState(): FilterHookState? = tile.hooks[direction.name] as? FilterHookState

	/** Whether this face can batch at all - the sync hook reuses this menu but has no buffer. */
	fun supportsBatching(): Boolean = filterState() != null

	/** How much of a matching resource must cross this face at once, in authored units - see [FilterHookState.batchSize]. */
	fun batchSize(): Long = filterState()?.effectiveBatchSize() ?: FilterHookState.NOT_BATCHED

	/** The largest batch this face can be set to - see [FilterHookState.maxBatchSize]. */
	fun maxBatchSize(): Long = FilterHookState.maxBatchSize

	private fun hookState(): HookHolderState? = tile.hooks[direction.name]

	/** Whether this hook has a [HookHolderState.recursive] setting at all - only one that provides does. */
	fun supportsRecursion(): Boolean = hookState()?.fromRegistry?.providesItems == true

	/** This hook's own [HookHolderState.recursive], as the client last received it. */
	fun isRecursive(): Boolean = hookState()?.recursive ?: false

	/**
	 * Whether this hook's routing priority and consignment colour mean anything to it.
	 *
	 * Both are *destination*-side settings: priority is how the router chooses between candidates it
	 * could deliver to, and colour is matched against a travelling consignment on arrival. A hook
	 * that only ever acts as a **source** is never either - it is read from, not routed to - so a
	 * provider ([PipeHookType.providesItems] without [PipeHookType.validRoute]) has no use for the
	 * pair of them. [net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookState] has said so in its
	 * own KDoc all along; this is the screen finally agreeing with it.
	 *
	 * A sync hook is both at once and keeps them.
	 */
	fun supportsRouting(): Boolean {
		val type = hookState()?.fromRegistry ?: return true
		return !(type.providesItems && !type.validRoute)
	}

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
