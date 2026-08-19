package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.crafting.CraftingJob

/** State for one [TerminalHookType] attachment - search results live in the menu/network, not persisted here; [jobs] is likewise runtime-only, not NBT-persisted - see [CraftingJob]'s own KDoc. */
class TerminalHookState : HookHolderState(TerminalHookType.ID) {
	/** Crafting requests submitted through this face, oldest first - only [jobs]'s head is ever advanced per tick, see [TerminalHookType.tick]. */
	val jobs: MutableList<CraftingJob> = mutableListOf()

	/**
	 * Real, physically-interactable slots a withdrawal or finished [CraftingJob] delivers into - a
	 * terminal is a self-contained delivery point, not something that needs an external chest wired
	 * to one of its other faces. Exposed directly on [net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity]'s
	 * own block position (see [net.kernelpanicsoft.tubularstorage.registry.TileRegistry.Hook]'s
	 * `exposeItemStorage`), not face-gated like [InterfaceHookState.stock] - a pipe delivering to
	 * this block should land here regardless of which face the last hop approaches from.
	 */
	val output: ArchieItemStorage by itemField(SLOT_COUNT)

	companion object {
		const val SLOT_COUNT = 9
	}
}
