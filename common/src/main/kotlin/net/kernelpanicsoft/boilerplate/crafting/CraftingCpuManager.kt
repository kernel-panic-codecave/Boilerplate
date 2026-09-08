package net.kernelpanicsoft.boilerplate.crafting

import net.kernelpanicsoft.boilerplate.pipe.encasement.AbstractMultiblockManager
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.WeakHashMap

/**
 * Tracks which pipe segments carrying a Crafting CPU encasement - a [CraftingBufferEncasementType]
 * - cluster together into one Crafting CPU, one instance per [ServerLevel] - mirrors
 * [net.kernelpanicsoft.boilerplate.pipe.network.PipeNetworkManager]'s own cached-topology idiom,
 * on top of [AbstractMultiblockManager]'s shared flood-fill/cache machinery (see its own KDoc, and
 * [net.kernelpanicsoft.boilerplate.power.PressureMultiblockManager] for the sibling that adds a
 * shape rule of its own). A [Cluster] is only [Cluster.valid] when its members fill their own
 * bounding box exactly - a 1x1x1, 2x2x1, 2x2x2... cuboid, the one rule [isValidShape] leaves at
 * `true` - so an L-shaped or otherwise incomplete arrangement is inert as a CPU: no member ticks
 * jobs ([CraftingCpuRuntime.advanceJob]), terminals can't submit to it
 * ([net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.reachableCraftingCpus]), and
 * each segment's storage stands alone ([CraftingCpuMemberState.combinedStorage]). Its
 * [Cluster.leader] is deterministically the lowest [BlockPos.asLong] among its members, so every
 * member computes the same answer without a persisted "controller" flag; only the leader of a valid
 * cluster actually drives job execution.
 */
class CraftingCpuManager private constructor() : AbstractMultiblockManager<CraftingCpuMemberState>() {
	/** Any Crafting CPU member counts, whatever its encasement type - see [craftingCpuMemberAt]. */
	override fun memberAt(level: ServerLevel, pos: BlockPos): CraftingCpuMemberState? = craftingCpuMemberAt(level, pos)

	/** No further shape rule beyond [AbstractMultiblockManager.clusterOf]'s own bounding-box check - a Crafting CPU may take any cuboid shape. */
	override fun isValidShape(level: ServerLevel, members: List<BlockPos>): Boolean = true

	companion object {
		private val byLevel = WeakHashMap<ServerLevel, CraftingCpuManager>()

		fun get(level: ServerLevel): CraftingCpuManager = byLevel.getOrPut(level) { CraftingCpuManager() }
	}
}
