package net.kernelpanicsoft.boilerplate.pipe.encasement

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * Shared BFS-flood-fill-and-cache multiblock clustering behind
 * [net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager] (a Crafting CPU - any encased
 * pipe segment cluster filling its own bounding box) and
 * [net.kernelpanicsoft.boilerplate.power.PressureMultiblockManager] (a compressor bank/auxiliary
 * tank - the same flood fill, with a further shape rule of its own on top of the bounding-box
 * check). [T] is whatever per-position payload [memberAt] resolves - a [Cluster] itself only ever
 * carries positions; [T] exists purely so [isValidShape] can inspect what occupies each member
 * without re-resolving it. One instance per [net.minecraft.server.level.ServerLevel], kept by each
 * subclass's own `WeakHashMap`-backed `get(level)` rather than here, since the cache itself isn't
 * shared across kinds.
 */
abstract class AbstractMultiblockManager<T : Any> {
	data class Cluster(val members: List<BlockPos>, val leader: BlockPos, val valid: Boolean)

	private val clusterOf = HashMap<BlockPos, Cluster>()

	/** Whatever payload occupies [pos] as a cluster member, or `null` if it isn't one. */
	protected abstract fun memberAt(level: ServerLevel, pos: BlockPos): T?

	/** Whether [members] (already confirmed to fill their own bounding box) is a genuinely valid structure - see each subclass's own shape rule. */
	protected abstract fun isValidShape(level: ServerLevel, members: List<BlockPos>): Boolean

	/**
	 * [pos]'s own cluster, recomputed via BFS and cached until [invalidate]d. [excluding] marks a
	 * position the flood fill must treat as already gone - the whole-segment removal path runs
	 * while the dying block entity is still registered ([net.minecraft.world.level.chunk.LevelChunk.setBlockState]
	 * calls the block's `onRemove` before dropping the BE), so without it a removal-time recomputation
	 * would rebuild - and re-cache - the very cluster the removal just broke.
	 */
	fun clusterOf(level: ServerLevel, pos: BlockPos, excluding: BlockPos? = null): Cluster {
		clusterOf[pos]?.let { return it }

		// The start node has to hold a member - without this check, a query landing on an empty
		// position (formation refreshes probe every neighbor of a change, occupied or not) would
		// mint a ghost one-member cluster for it and cache it under that key, poisoning every later
		// lookup with a member that does not exist.
		if (memberAt(level, pos) == null) return Cluster(emptyList(), pos, false)

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
				if (memberAt(level, neighborPos) == null) continue
				queue += neighborPos
			}
		}

		// A component too big for the cap was truncated mid-fill and can't be verified as a complete
		// structure of supported size - invalid outright rather than silently half-functioning.
		val valid = queue.isEmpty() && fillsOwnBoundingBox(members) && isValidShape(level, members)
		val cluster = Cluster(members, members.minBy { it.asLong() }, valid)
		for (member in members) clusterOf[member] = cluster
		return cluster
	}

	/** Drops the cached cluster containing [pos] (and every other member it had) - call on placement/removal. */
	fun invalidate(pos: BlockPos) {
		val cluster = clusterOf.remove(pos) ?: return
		for (member in cluster.members) clusterOf.remove(member)
	}

	companion object {
		/** Caps the BFS flood fill - hitting it (a component larger than this) leaves the cluster [Cluster.valid]-false, since no supported structure is that big to verify against. */
		const val MAX_CLUSTER_SIZE = 64

		/** Whether [members] fill their own axis-aligned bounding box exactly - the one structural rule every multiblock kind here shares. */
		private fun fillsOwnBoundingBox(members: List<BlockPos>): Boolean {
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
	}
}
