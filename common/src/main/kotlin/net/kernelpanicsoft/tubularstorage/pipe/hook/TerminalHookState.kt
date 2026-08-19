package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.tubularstorage.crafting.CraftingJob

/** State for one [TerminalHookType] attachment - search results live in the menu/network, not persisted here; [jobs] is likewise runtime-only, not NBT-persisted - see [CraftingJob]'s own KDoc. */
class TerminalHookState : HookHolderState(TerminalHookType.ID) {
	/** Crafting requests submitted through this face, oldest first - only [jobs]'s head is ever advanced per tick, see [TerminalHookType.tick]. */
	val jobs: MutableList<CraftingJob> = mutableListOf()
}
