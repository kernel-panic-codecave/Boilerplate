package net.kernelpanicsoft.boilerplate.pipe.hook

import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.boilerplate.crafting.SubmittedJobRef
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import net.kernelpanicsoft.boilerplate.resource.ResourceKind
import net.kernelpanicsoft.boilerplate.resource.ResourceStorage
import net.kernelpanicsoft.boilerplate.resource.resourceField
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackFluidStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackItemStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackResourceStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.minecraft.resources.ResourceLocation

/**
 * State for one [TerminalHookType] attachment - search results live in the menu/network, not
 * persisted here; [submittedJobs] is likewise runtime-only, not NBT-persisted. [type] is
 * overridable (not hardcoded to [TerminalHookType.ID]) so
 * [net.kernelpanicsoft.boilerplate.pipe.hook.CraftingTerminalHookState] can extend this class
 * and inherit [submittedJobs]/[output] wholesale rather than duplicating them.
 */
open class TerminalHookState(type: ResourceLocation = TerminalHookType.ID) : HookHolderState(type), FallbackItemStorageExposer, FallbackFluidStorageExposer, FallbackResourceStorageExposer {
	/** Jobs submitted through this face, oldest first, wherever they actually run - a Crafting CPU cluster owns execution; this is just a pointer to it - see [advanceTerminalJobs]. */
	val submittedJobs: MutableList<SubmittedJobRef> = mutableListOf()

	/**
	 * Real, physically-interactable cells a withdrawal delivers into, holding **any** registered
	 * kind - a terminal is a self-contained delivery point, not something that needs an external
	 * chest wired to one of its other faces, and that is as true of a fluid as of an item.
	 *
	 * One mixed row rather than item slots plus a tank plus a buffer per addon kind: a withdrawal
	 * lands in whichever column is free, and what a column holds is whatever was withdrawn into it.
	 * Exposed directly on [net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity]'s own
	 * block position (see [net.kernelpanicsoft.boilerplate.registry.TileRegistry.Multipart]), not
	 * face-gated like [InterfaceHookState.stock] - a pipe delivering to this block should land here
	 * regardless of which face the last hop approaches from. A finished crafting job never lands
	 * here - see [advanceTerminalJobs]'s own KDoc.
	 */
	val output: ResourceStorage by resourceField(SLOT_COUNT, capacity = BoilerplateConfig.Gameplay.Capacities.terminalInboxMillibuckets)

	/**
	 * Where a withdrawal of [kind] should be delivered - [output] seen as that one kind, so a
	 * column another kind holds reads as this one's own blank.
	 *
	 * The single question the withdrawal path asks, so it never learns which kinds exist.
	 */
	fun inboxFor(kind: ResourceKind): CommonStorage<*>? = output.viewOf(kind)

	/**
	 * A delivery of any kind lands in [output], reservation-aware for everything on the network but
	 * the reserved delivery itself - see [ReservedSlotStorage], and [exposedItemStorage] for the
	 * typed face built on this.
	 */
	@Suppress("UNCHECKED_CAST")
	override fun exposedStorage(tile: MultipartBlockEntity, kind: ResourceKind): CommonStorage<*>? =
		inboxFor(kind)?.let { ReservedSlotStorage(it as CommonStorage<ResourceComponent>) { reservedSlots } }

	@Suppress("UNCHECKED_CAST")
	override fun exposedFluidStorage(tile: MultipartBlockEntity): CommonStorage<FluidResource>? =
		exposedStorage(tile, ResourceKindRegistry.Fluid) as CommonStorage<FluidResource>?

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
	fun reserveOutputSlot(resource: ResourceComponent): Pair<Int, Long>? {
		val reserved = reservedSlots
		for (index in 0 until output.size()) {
			if (index in reserved) continue
			// Free means free of *every* kind: a column some other kind holds is exactly the one a
			// delivery must not be promised, however empty it looks through one kind's own view.
			if (output.ownerOf(index) != null) continue
			val limit = output.getLimit(index, resource)
			if (limit <= 0) continue
			return index to limit
		}
		return null
	}

	/** Reservation-aware for everything *else* on the network - see [ReservedSlotStorage]. A reserved delivery's own arrival bypasses this and writes straight to [output]. */
	@Suppress("UNCHECKED_CAST")
	override fun exposedItemStorage(tile: MultipartBlockEntity): CommonStorage<ItemResource>? =
		exposedStorage(tile, ResourceKindRegistry.Item) as CommonStorage<ItemResource>?

	companion object {
		const val SLOT_COUNT = 9
	}
}
