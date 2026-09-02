package net.kernelpanicsoft.boilerplate.crafting

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.pipe.attachment.ItemStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity

/**
 * The **item**-holding member of a Crafting CPU multiblock - see [CraftingCpuMemberState] for the
 * job queue every member kind shares, and [CraftingBufferEncasementType] for the job execution it
 * feeds. [localStorage] is this member's own slice of the cluster's combined item pool
 * ([CraftingCpuMemberState.combinedStorage] concatenates every buffer member's own), so placing
 * another encased segment against the cluster is what grows its capacity.
 *
 * A cluster wanting to stage *fluids* takes a [CraftingTankEncasementState] alongside these.
 */
class CraftingBufferEncasementState : CraftingCpuMemberState(CraftingBufferEncasementType.ID), ItemStorageExposer {

	val localStorage: ArchieItemStorage by itemField(LOCAL_SLOTS)

	override val localItemStorage: ArchieItemStorage get() = localStorage

	// formed: Boolean - see EncasementHolderState.formed. Recomputed server-side for every member
	// a cluster change could have flipped (CraftingBufferEncasementType.onAttached's refresh) and
	// synced to clients, whose render-state reads it to gate the casing's edge/corner pieces:
	// those need whole-cluster knowledge no single client-side neighborhood probe can reconstruct
	// cheaply.

	/** This member's own cluster's combined item pool - also this state's own [ItemStorageExposer] answer. */
	override fun exposedItemStorage(tile: MultipartBlockEntity): CommonStorage<ItemResource> = combinedStorage(tile)

	companion object {
		/** [localStorage]'s own slot count - the unit a cluster's combined item capacity grows by per encased segment. */
		private const val LOCAL_SLOTS = 9
	}
}
