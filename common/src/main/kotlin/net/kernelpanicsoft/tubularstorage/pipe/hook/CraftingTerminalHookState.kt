package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.transfer.ArchieItemStorage

/**
 * A [TerminalHookState] upgraded with a real 3x3 [grid]/[result] pair - "instant" network-backed
 * crafting (fill the grid, hit Craft), distinct from [PatternProviderHookType]'s slow, pattern-driven
 * processing - see `docs/design/m4-crafting-automation.md`. Inherits [TerminalHookState.jobs]/
 * [TerminalHookState.output] wholesale; the Store/Craft tabs and job-tree view work identically to
 * a plain terminal.
 */
class CraftingTerminalHookState : TerminalHookState(CraftingTerminalHookType.ID) {
	val grid: ArchieItemStorage by itemField(GRID_SIZE)
	val result: ArchieItemStorage by itemField(1)

	companion object {
		const val GRID_SIZE = 9
	}
}
