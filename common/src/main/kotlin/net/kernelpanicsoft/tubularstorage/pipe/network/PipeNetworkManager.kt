package net.kernelpanicsoft.tubularstorage.pipe.network

import net.kernelpanicsoft.tubularstorage.pipe.block.HookBlock
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import java.util.UUID
import java.util.WeakHashMap

/**
 * Tracks which pipe positions belong to which [PipeNetwork], one instance per [ServerLevel].
 *
 * Populated reactively as [net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity]s load
 * (union-find merge, see [ensureRegistered]) rather than from persisted graph data - state is
 * always derived from the pipes actually present, so there's nothing to desync from world data.
 * Removal can split a network, which [ensureRegistered]'s cheap merge can't detect; that case is
 * handled by a chunked BFS rebuild spread across [tick] calls (see [scheduleRebuild]).
 *
 * [SubnetBoundary.isBoundaryEdge] is the one exception to "any two adjacent pipe/hook positions
 * merge": an edge it flags never merges its two sides, no matter how many other regular
 * connections eventually join them from elsewhere - see its own KDoc.
 */
class PipeNetworkManager {
	private val networkOf = HashMap<BlockPos, UUID>()
	private val networks = HashMap<UUID, PipeNetwork>()

	private var rebuildRemaining: MutableSet<BlockPos>? = null
	private val rebuildFrontier = ArrayDeque<BlockPos>()
	private var rebuildCurrentNetwork: PipeNetwork? = null

	fun networkIdAt(pos: BlockPos): UUID? = networkOf[pos]
	fun network(id: UUID): PipeNetwork? = networks[id]

	/** Registers [pos] (assumed to already be a placed/loaded pipe) into the network graph, merging with any connected neighbors. Idempotent. */
	fun ensureRegistered(level: ServerLevel, pos: BlockPos) {
		if (networkOf.containsKey(pos)) return

		val network = PipeNetwork(UUID.randomUUID())
		networks[network.id] = network
		network.members += pos
		networkOf[pos] = network.id

		for (direction in Direction.entries) {
			val neighborPos = pos.relative(direction)
			if (!PipeRouter.isPipe(level, neighborPos)) continue
			if (SubnetBoundary.isBoundaryEdge(level, pos, direction)) continue
			ensureRegistered(level, neighborPos)
			val ownId = networkOf.getValue(pos)
			val neighborId = networkOf.getValue(neighborPos)
			if (ownId != neighborId) mergeInto(ownId, neighborId)
		}

		networks.getValue(networkOf.getValue(pos)).version++
	}

	/** Unregisters [pos], scheduling the rest of its former network for a chunked rebuild since removal may have split it. */
	fun onRemoved(pos: BlockPos) {
		val networkId = networkOf.remove(pos) ?: return
		val network = networks.remove(networkId) ?: return
		network.members -= pos
		if (network.members.isEmpty()) return

		for (memberPos in network.members) networkOf.remove(memberPos)
		val remaining = rebuildRemaining
		if (remaining != null) remaining += network.members else rebuildRemaining = network.members
	}

	/** Advances the chunked network-split rebuild (if one is pending) by up to [REBUILD_BUDGET_PER_TICK] positions. */
	fun tick() {
		val remaining = rebuildRemaining ?: return
		var budget = REBUILD_BUDGET_PER_TICK
		while (budget > 0) {
			if (rebuildFrontier.isEmpty()) {
				val seed = remaining.firstOrNull()
				if (seed == null) {
					rebuildRemaining = null
					rebuildCurrentNetwork = null
					return
				}
				remaining -= seed
				val network = PipeNetwork(UUID.randomUUID())
				networks[network.id] = network
				rebuildCurrentNetwork = network
				rebuildFrontier += seed
			}

			val pos = rebuildFrontier.removeFirst()
			val network = rebuildCurrentNetwork!!
			if (pos in network.members) continue
			network.members += pos
			networkOf[pos] = network.id
			budget--

			for (direction in Direction.entries) {
				val neighborPos = pos.relative(direction)
				if (neighborPos in remaining) rebuildFrontier += neighborPos
			}
		}
	}

	private fun mergeInto(targetId: UUID, sourceId: UUID) {
		val target = networks.getValue(targetId)
		val source = networks.remove(sourceId) ?: return
		for (memberPos in source.members) networkOf[memberPos] = targetId
		target.members += source.members
	}


	companion object {
		private const val REBUILD_BUDGET_PER_TICK = 500

		private val byLevel = WeakHashMap<ServerLevel, PipeNetworkManager>()

		fun get(level: ServerLevel): PipeNetworkManager = byLevel.getOrPut(level) { PipeNetworkManager() }
	}
}
