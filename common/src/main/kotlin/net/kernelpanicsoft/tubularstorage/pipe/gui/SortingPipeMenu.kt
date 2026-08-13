package net.kernelpanicsoft.tubularstorage.pipe.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.minecraft.world.entity.player.Inventory

/** Menu for a sorting-module-equipped [PipeBlockEntity]'s filter grid, mode/priority/color config. */
class SortingPipeMenu(id: Int, inventory: Inventory, tile: PipeBlockEntity) :
	ComposeBlockContainerMenu<PipeBlockEntity, SortingPipeMenu>(GuiRegistry.SortingPipe, id, inventory, tile) {

	override fun registerSlotHandlers() {
		handler("filter", tile.filter)
	}
}
