package net.kernelpanicsoft.tubularstorage.crafting.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.minecraft.world.entity.player.Inventory

/**
 * Menu for [AssemblyTableBlockEntity]: just [grid][AssemblyTableBlockEntity.grid]/
 * [output][AssemblyTableBlockEntity.output] slots and a readout of what's currently running - no
 * pattern authoring here, that's a Pattern Terminal's own job now (see
 * `docs/design/m4-crafting-automation.md`).
 */
class AssemblyTableMenu(id: Int, inventory: Inventory, tile: AssemblyTableBlockEntity) :
	ComposeBlockContainerMenu<AssemblyTableBlockEntity, AssemblyTableMenu>(GuiRegistry.AssemblyTable, id, inventory, tile) {

	val activePattern: Pattern? get() = tile.activePattern

	override fun registerSlotHandlers() {
		handler("grid", tile.grid)
		handler("output", tile.output)
	}
}
