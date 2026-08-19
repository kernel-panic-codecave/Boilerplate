package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.transfer.ArchieItemStorage

/**
 * A [TerminalHookState] upgraded with a real 3x3 [grid] - "instant" network-backed crafting, the
 * same live recipe-derived preview + click/shift-click-to-take vanilla crafting tables use (see
 * [net.kernelpanicsoft.tubularstorage.pipe.gui.CraftingTerminalHookMenu.craftOnce]) rather than a
 * real, holdable result slot of its own - distinct from [PatternProviderHookType]'s slow,
 * pattern-driven processing. Inherits [TerminalHookState.jobs]/[TerminalHookState.output]
 * wholesale; the Store/Craft tabs and job-tree view work identically to a plain terminal.
 */
class CraftingTerminalHookState : TerminalHookState(CraftingTerminalHookType.ID) {
	val grid: ArchieItemStorage by itemField(GRID_SIZE)

	companion object {
		const val GRID_SIZE = 9
	}
}
