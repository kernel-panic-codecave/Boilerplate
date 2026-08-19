package net.kernelpanicsoft.tubularstorage.pipe.gui

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.tubularstorage.network.SItemResource
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Inventory

/** Menu for the sorting hook attached to [tile]'s [direction] face: its filter grid and mode/priority/color config. */
class SortingHookMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<HookBlockEntity, SortingHookMenu>(GuiRegistry.SortingHook, id, inventory, tile) {

	/** [tile]'s own position - exposed since [tile] itself is `protected`, for the C2S routing-edit/ghost-slot packets. */
	val pos: BlockPos get() = tile.blockPos

	/** [direction]'s current [RoutingModule], read once when the screen opens - see `docs/design/m2-sorting-routing.md`. */
	fun currentRouting(): RoutingModule = (tile.hooks[direction.name] as? SortingHookState)?.routing ?: RoutingModule()

	/** [direction]'s current ghost filter grid, read once when the screen opens - same "not wired into live sync" reasoning as [currentRouting]. */
	fun currentFilter(): List<SItemResource> = (tile.hooks[direction.name] as? SortingHookState)?.filter?.toList() ?: List(9) { ItemResource.BLANK }

	override fun registerSlotHandlers() {
		// No "filter" slot group - the ghost grid is rendered/edited entirely through Compose
		// (see GhostSlotGrid), not a real vanilla Slot-backed handler.
	}
}
