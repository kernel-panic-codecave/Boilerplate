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

	/** [output] slot indices currently claimed by a [PendingDelivery] - see [ReservedSlotStorage]. */
	val reservedSlots: Set<Int> get() = pendingDeliveries.mapTo(mutableSetOf()) { it.slot }

	/**
	 * The lowest [output] slot that's genuinely empty *and* not already claimed by a
	 * [PendingDelivery], paired with the most of [resource] it could hold - or `null` when the inbox
	 * has no room left to promise anyone, which is a request's cue to refuse rather than dispatch
	 * something with nowhere to land.
	 *
	 * Reserving is not a side effect here: a slot only actually becomes reserved once a
	 * [PendingDelivery] naming it is added to [pendingDeliveries], which callers do from
	 * [net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.request]'s own `onDispatch` -
	 * so a request that finds no source never leaves a slot stranded as reserved-but-never-coming.
	 * That also means a caller reserving several slots in one pass must add each delivery before
	 * asking for the next slot, which the `onDispatch` ordering gives it for free.
	 */
	fun reserveOutputSlot(resource: ItemResource): Pair<Int, Long>? {
		val reserved = reservedSlots
		for (index in 0 until output.size()) {
			if (index in reserved) continue
			if (!output.getResource(index).isBlank) continue
			val limit = output.getLimit(index, resource)
			if (limit <= 0) continue
			return index to limit
		}
		return null
	}

	/** Reservation-aware for everything *else* on the network - see [ReservedSlotStorage]. A reserved delivery's own arrival bypasses this and writes straight to [output]. */
	override fun exposedItemStorage(tile: MultipartBlockEntity): CommonStorage<ItemResource> =
		ReservedSlotStorage(output) { reservedSlots }

	companion object {
		const val SLOT_COUNT = 9
	}
}
