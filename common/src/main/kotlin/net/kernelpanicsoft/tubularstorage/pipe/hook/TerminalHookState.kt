package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.crafting.SubmittedJobRef
import net.minecraft.resources.ResourceLocation

/**
 * State for one [TerminalHookType] attachment - search results live in the menu/network, not
 * persisted here; [submittedJobs] is likewise runtime-only, not NBT-persisted. [type] is
 * overridable (not hardcoded to [TerminalHookType.ID]) so
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.CraftingTerminalHookState] can extend this class
 * and inherit [submittedJobs]/[output] wholesale rather than duplicating them.
 */
open class TerminalHookState(type: ResourceLocation = TerminalHookType.ID) : HookHolderState(type) {
	/** Jobs submitted through this face, oldest first, wherever they actually run - a Crafting CPU cluster owns execution now, this is just a pointer to it - see [advanceTerminalJobs]. */
	val submittedJobs: MutableList<SubmittedJobRef> = mutableListOf()

	/**
	 * Real, physically-interactable slots a withdrawal delivers into - a terminal is a
	 * self-contained delivery point, not something that needs an external chest wired to one of its
	 * other faces. Exposed directly on [net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity]'s
	 * own block position (see [net.kernelpanicsoft.tubularstorage.registry.TileRegistry.Multipart]'s
	 * `exposeItemStorage`), not face-gated like [InterfaceHookState.stock] - a pipe delivering to
	 * this block should land here regardless of which face the last hop approaches from. A
	 * finished crafting job never lands here - see [advanceTerminalJobs]'s own KDoc.
	 */
	val output: ArchieItemStorage by itemField(SLOT_COUNT)

	companion object {
		const val SLOT_COUNT = 9
	}
}
