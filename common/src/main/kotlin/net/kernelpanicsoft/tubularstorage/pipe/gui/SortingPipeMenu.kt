package net.kernelpanicsoft.tubularstorage.pipe.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Inventory

/** Menu for the sorting hook attached to [tile]'s [direction] face: its filter grid and mode/priority/color config. */
class SortingPipeMenu(id: Int, inventory: Inventory, tile: PipeBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<PipeBlockEntity, SortingPipeMenu>(GuiRegistry.SortingPipe, id, inventory, tile) {

	override fun registerSlotHandlers() {
		handler("filter", tile.filterFor(direction))
	}
}
