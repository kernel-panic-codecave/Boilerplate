package net.kernelpanicsoft.boilerplate.crafting

import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.fluid.util.FluidAmounts
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.archie.transfer.ArchieFluidStorage
import net.kernelpanicsoft.boilerplate.pipe.attachment.FluidStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity

/**
 * The **fluid**-holding member of a Crafting CPU multiblock - a Crafting Tank. The exact
 * counterpart of [CraftingBufferEncasementState]: same cluster ([CraftingCpuManager] resolves both
 * through [craftingCpuMemberAt]), same shared job queue ([CraftingCpuMemberState]), contributing
 * fluid tanks to the cluster's pool instead of item slots.
 *
 * Exists because a [Pattern] may name a fluid on either side now (`1000mB water + 1 clay -> …`).
 * Feeding such a step means the CPU has to *hold* that fluid first, and item slots cannot; a
 * cluster with no tank in it simply can't run a fluid-bearing pattern, exactly as a cluster with no
 * buffer can't hold an item.
 *
 * [LOCAL_SLOTS] separate tanks rather than one big one: a single processing step routinely wants
 * two distinct fluids at once, and one shared tank could only ever hold whichever arrived first.
 */
class CraftingTankEncasementState : CraftingCpuMemberState(CraftingTankEncasementType.ID), FluidStorageExposer {

	val localTanks: ArchieFluidStorage by fluidField(tankCapacity(), size = LOCAL_SLOTS)

	override val localFluidStorage: ArchieFluidStorage get() = localTanks

	/** This member's own cluster's combined fluid pool - also this state's own [FluidStorageExposer] answer, which is what makes a Crafting Tank a real fluid-network destination. */
	override fun exposedFluidStorage(tile: MultipartBlockEntity): CommonStorage<FluidResource> = combinedFluidStorage(tile)

	companion object {
		/** How many separate tanks one encased segment contributes. */
		private const val LOCAL_SLOTS = 4

		/** Each tank's own capacity, in millibuckets - the loader-independent way to state it. */
		const val TANK_CAPACITY_MILLIBUCKETS = 16_000L

		/**
		 * [TANK_CAPACITY_MILLIBUCKETS] in whatever unit this platform counts fluid in.
		 *
		 * A function rather than a `val`, and routed through [FluidAmounts.toPlatformAmount] rather
		 * than any `FluidAmounts` constant, for exactly the reason
		 * [net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity.capacity] documents:
		 * those constants all read `0` in Common Storage Lib 0.0.5, and a zero-capacity tank
		 * silently accepts nothing.
		 */
		fun tankCapacity(): Long = FluidAmounts.toPlatformAmount(TANK_CAPACITY_MILLIBUCKETS)
	}
}
