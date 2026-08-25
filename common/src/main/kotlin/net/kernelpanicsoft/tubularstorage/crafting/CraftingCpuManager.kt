package net.kernelpanicsoft.tubularstorage.crafting

import net.kernelpanicsoft.tubularstorage.crafting.CraftingCpuManager.Companion.MAX_CLUSTER_SIZE
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import java.util.WeakHashMap
import kotlin.collections.ArrayDeque

/**
 * Tracks which pipe segments carrying a [CraftingBufferEncasementType] encasement cluster together
 * into one Crafting CPU, one instance per [ServerLevel] - mirrors
 * [net.kernelpanicsoft.tubularstorage.pipe.network.PipeNetworkManager]'s own cached-topology idiom.
 * A [Cluster] is every axis-adjacent encased segment reachable from a given position (BFS, capped at
 * [MAX_CLUSTER_SIZE]); it is only [Cluster.valid] when those members fill their own bounding box
 * exactly - a 1x1x1, 2x2x1, 2x2x2... cuboid - so an L-shaped or otherwise incomplete arrangement is
 * inert as a CPU: no member ticks jobs ([CraftingBufferEncasementType.tick]), terminals can't submit
 * to it ([net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment.reachableCraftingCpus]),
 * and each segment's storage stands alone ([CraftingBufferEncasementState.combinedStorage]). Its
 * [Cluster.leader] is deterministically the lowest [BlockPos.asLong] among its members, so every
 * member computes the same answer without a persisted "controller" flag; only the leader of a valid
 * cluster actually drives job execution.
 */
class CraftingCpuManager {
	data class Cluster(val members: List<BlockPos>, val leader: BlockPos, val valid: Boolean)

	private val clusterOf = HashMap<BlockPos, Cluster>()

	/**
	 * [pos]'s own cluster, recomputed via BFS and cached until [invalidate]d. [excluding] marks a
	 * position the flood fill must treat as already gone - the whole-segment removal path runs
	 * while the dying block entity is still registered ([net.minecraft.world.level.chunk.LevelChunk.setBlockState]
	 * calls the block's `onRemove` before dropping the BE), so without it a removal-time recomputation
	 * would rebuild - and re-cache - the very cluster the removal just broke.
	 */
	fun clusterOf(level: ServerLevel, pos: BlockPos, excluding: BlockPos? = null): Cluster {
		clusterOf[pos]?.let { return it }

		// The start node has to hold a buffer - without this check, a query landing on an empty
		// position (formation refreshes probe every neighbor of a change, occupied or not) would
		// mint a ghost one-member cluster for it and cache it under that key, poisoning every later
		// lookup with a member that does not exist.
		if (craftingBufferAt(level, pos) == null) return Cluster(emptyList(), pos, false)

		val members = mutableListOf<BlockPos>()
		val visited = hashSetOf(pos)
		val queue = ArrayDeque<BlockPos>()
		queue += pos
		while (queue.isNotEmpty() && members.size < MAX_CLUSTER_SIZE) {
			val current = queue.removeFirst()
			members += current
			for (direction in Direction.entries) {
				val neighborPos = current.relative(direction)
				if (!visited.add(neighborPos)) continue
				if (neighborPos == excluding) continue
				if (craftingBufferAt(level, neighborPos) == null) continue
				queue += neighborPos
			}
		}

		// A component too big for the cap was truncated mid-fill and can't be verified as a complete
		// cuboid of supported size - invalid outright rather than silently half-functioning.
		val valid = queue.isEmpty() && fillsOwnBoundingbox(members)
		val cluster = Cluster(members, members.minBy { it.asLong() }, valid)
		for (member in members) clusterOf[member] = cluster
		return cluster
	}

	/** Whether [members] fill their own axis-aligned bounding box exactly - the one shape a Crafting CPU may take. */
	private fun fillsOwnBoundingbox(members: List<BlockPos>): Boolean {
		var minX = Int.MAX_VALUE
		var minY = Int.MAX_VALUE
		var minZ = Int.MAX_VALUE
		var maxX = Int.MIN_VALUE
		var maxY = Int.MIN_VALUE
		var maxZ = Int.MIN_VALUE
		for (pos in members) {
			minX = minOf(minX, pos.x); maxX = maxOf(maxX, pos.x)
			minY = minOf(minY, pos.y); maxY = maxOf(maxY, pos.y)
			minZ = minOf(minZ, pos.z); maxZ = maxOf(maxZ, pos.z)
		}
		return members.size == (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1)
	}

	/** Drops the cached cluster containing [pos] (and every other member it had) - call on placement/removal. */
	fun invalidate(pos: BlockPos) {
		val cluster = clusterOf.remove(pos) ?: return
		for (member in cluster.members) clusterOf.remove(member)
	}

	companion object {
		/** Caps the BFS flood fill, matching every other flat-baseline cap in this subsystem - hitting it (a component larger than this) leaves the cluster [Cluster.valid]-false, since no supported cuboid is that big to verify against. */
		private const val MAX_CLUSTER_SIZE = 64

		private val byLevel = WeakHashMap<ServerLevel, CraftingCpuManager>()

		fun get(level: ServerLevel): CraftingCpuManager = byLevel.getOrPut(level) { CraftingCpuManager() }
	}
}
