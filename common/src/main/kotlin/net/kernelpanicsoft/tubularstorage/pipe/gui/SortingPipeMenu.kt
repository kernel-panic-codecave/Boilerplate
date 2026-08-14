package net.kernelpanicsoft.tubularstorage.pipe.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Inventory

/** Menu for the sorting hook attached to [tile]'s [direction] face: its filter grid and mode/priority/color config. */
class SortingPipeMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<HookBlockEntity, SortingPipeMenu>(GuiRegistry.SortingPipe, id, inventory, tile) {

	/** [tile]'s own position - exposed since [tile] itself is `protected`, for the C2S routing-edit packet. */
	val pos: BlockPos get() = tile.blockPos

	/** [direction]'s current [RoutingModule], read once when the screen opens - see `docs/design/m2-sorting-routing.md`. */
	fun currentRouting(): RoutingModule = (tile.hooks[direction.name] as? SortingHookState)?.routing ?: RoutingModule()

	override fun registerSlotHandlers() {
		handler("filter", tile.filterFor(direction))
	}
}
