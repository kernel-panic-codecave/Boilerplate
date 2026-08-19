package net.kernelpanicsoft.tubularstorage.crafting.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.crafting.PatternEncoder
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory

/**
 * Menu for [AssemblyTableBlockEntity]: real [grid][AssemblyTableBlockEntity.grid]/
 * [output][AssemblyTableBlockEntity.output] slots, shared between manually authoring a new
 * [Pattern] (this class's own [encode], delegating the actual snapshot logic to [PatternEncoder])
 * and - once pipe-fed processing lands - running an already-encoded one against the same slots.
 */
class AssemblyTableMenu(id: Int, inventory: Inventory, tile: AssemblyTableBlockEntity) :
	ComposeBlockContainerMenu<AssemblyTableBlockEntity, AssemblyTableMenu>(GuiRegistry.AssemblyTable, id, inventory, tile) {

	val patterns: List<Pattern> get() = tile.patterns

	override fun registerSlotHandlers() {
		handler("grid", tile.grid)
		handler("output", tile.output)
	}

	fun encode() {
		val level = level as? ServerLevel ?: return
		PatternEncoder.encode(level, tile)
	}

	/** Removes the pattern at [index], if any - the simplest possible "undo" for a mis-encoded entry. */
	fun removePattern(index: Int) {
		if (index !in tile.patterns.indices) return
		tile.patterns.removeAt(index)
		tile.setChanged()
	}
}
