package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.crafting.SubmittedJobRef
import net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackItemStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.minecraft.resources.ResourceLocation

/**
 * State for one [TerminalHookType] attachment - search results live in the menu/network, not
 * persisted here; [submittedJobs] is likewise runtime-only, not NBT-persisted. [type] is
 * overridable (not hardcoded to [TerminalHookType.ID]) so
 * [net.kernelpanicsoft.boilerplate.pipe.hook.CraftingTerminalHookState] can extend this class
 * and inherit [submittedJobs]/[output] wholesale rather than duplicating them.
 */
open class TerminalHookState(type: ResourceLocation = TerminalHookType.ID) : HookHolderState(type), FallbackItemStorageExposer {
	/** Jobs submitted through this face, oldest first, wherever they actually run - a Crafting CPU cluster owns execution now, this is just a pointer to it - see [advanceTerminalJobs]. */
	val submittedJobs: MutableList<SubmittedJobRef> = mutableListOf()

	/**
	 * Real, physically-interactable slots a withdrawal delivers into - a terminal is a
	 * self-contained delivery point, not something that needs an external chest wired to one of its
	 * other faces. Exposed directly on [net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity]'s
	 * own block position (see [net.kernelpanicsoft.boilerplate.registry.TileRegistry.Multipart]'s
	 * `exposeItemStorage`), not face-gated like [InterfaceHookState.stock] - a pipe delivering to
	 * this block should land here regardless of which face the last hop approaches from. A
	 * finished crafting job never lands here - see [advanceTerminalJobs]'s own KDoc.
	 */
	val output: ArchieItemStorage by itemField(SLOT_COUNT)

	/** In-flight deliveries [output] doesn't have the real item for yet - see [PendingDelivery]'s own KDoc. Not tied to a specific [output] slot index; [AbstractTerminalHookScreen] assigns each to the next empty output slot purely for rendering, in order. */
	val pendingDeliveries by listField(PendingDelivery.serializer()) { emptyList<PendingDelivery>() }

	/** Backing counter for [nextReservationId] - a plain incrementing [Long], persisted so a reservation dispatched just before a save/reload still gets an id no future reservation this hook creates could collide with. */
	private var reservationCounter: Long by longField()

	/** A fresh [PendingDelivery.id], never before used by this hook. */
	fun nextReservationId(): Long = reservationCounter++

	override fun exposedItemStorage(tile: MultipartBlockEntity): CommonStorage<ItemResource> = output

	companion object {
		const val SLOT_COUNT = 9
	}
}
