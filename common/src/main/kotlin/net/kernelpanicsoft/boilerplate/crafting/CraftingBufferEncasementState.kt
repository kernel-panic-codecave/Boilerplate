package net.kernelpanicsoft.boilerplate.crafting

import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.boilerplate.resource.ResourceKind
import net.kernelpanicsoft.boilerplate.resource.ResourceStorage
import net.kernelpanicsoft.boilerplate.resource.resourceField
import net.kernelpanicsoft.boilerplate.pipe.attachment.FluidStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.attachment.ItemStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.attachment.ResourceStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity

/**
 * A staging member of a Crafting CPU multiblock - see [CraftingCpuMemberState] for the job queue
 * every member kind shares, and [CraftingBufferEncasementType] for the job execution it feeds.
 * [localStorage] is this member's own slice of the cluster's pools
 * ([CraftingCpuMemberState.combinedFor] concatenates every member's own), so placing another encased
 * segment against the cluster is what grows its capacity.
 *
 * Holds **any registered kind**, in one mixed row. A pattern may name a fluid or an addon's chemical
 * on either side, and a buffer that could only hold items had nowhere to stage one - which is what
 * the Crafting Tank existed to work around. Once a buffer could hold anything the tank was a second
 * block doing a strictly smaller job, so it is gone and this is the only member kind.
 */
class CraftingBufferEncasementState :
	CraftingCpuMemberState(CraftingBufferEncasementType.ID), ItemStorageExposer, FluidStorageExposer, ResourceStorageExposer {

	/**
	 * This member's own staging row - [LOCAL_SLOTS] slots, each taking whatever claims it first.
	 *
	 * The configured buffer capacity is what one slot holds of a *measured* kind, and it is sized
	 * generously on purpose: a buffer is the only place a cluster stages fluid, so this figure alone
	 * decides how much of one a job can hold at a time.
	 */
	val localStorage: ResourceStorage by resourceField(LOCAL_SLOTS, capacity = BoilerplateConfig.Gameplay.Capacities.craftingBufferMillibuckets)

	/** Every registered kind, since this row holds whatever lands in it - see [CraftingCpuMemberState.localStorageFor]. */
	override fun localStorageFor(kind: ResourceKind): CommonStorage<*>? = localStorage.viewOf(kind)

	// formed: Boolean - see EncasementHolderState.formed. Recomputed server-side for every member
	// a cluster change could have flipped (CraftingBufferEncasementType.onAttached's refresh) and
	// synced to clients, whose render-state reads it to gate the casing's edge/corner pieces:
	// those need whole-cluster knowledge no single client-side neighborhood probe can reconstruct
	// cheaply.

	/** This member's own cluster's combined pool of [kind] - what makes an encased segment a real network destination for it. */
	override fun exposedStorage(tile: MultipartBlockEntity, kind: ResourceKind): CommonStorage<*>? = combinedFor(tile, kind)

	/** The item face of [exposedStorage] - see there. */
	override fun exposedItemStorage(tile: MultipartBlockEntity): CommonStorage<ItemResource> = combinedStorage(tile)

	/**
	 * The fluid face of [exposedStorage].
	 *
	 * A buffer answers for fluid now, not only a tank: it can genuinely hold some, and a CPU whose
	 * only member was a buffer could otherwise never be *given* the fluid a step needed.
	 */
	override fun exposedFluidStorage(tile: MultipartBlockEntity): CommonStorage<FluidResource> = combinedFluidStorage(tile)

	companion object {
		/** [localStorage]'s own slot count - the unit a cluster's combined capacity grows by per encased segment. */
		private const val LOCAL_SLOTS = 9

	}
}
