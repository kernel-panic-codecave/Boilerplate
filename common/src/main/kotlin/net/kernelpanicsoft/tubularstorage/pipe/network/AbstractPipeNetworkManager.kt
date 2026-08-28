package net.kernelpanicsoft.tubularstorage.pipe.network

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * Tracks which positions belong to which [N] network, one instance per [ServerLevel] - the shared
 * base behind [PipeNetworkManager] (item pipes) and
 * [net.kernelpanicsoft.tubularstorage.power.network.PressurePipeNetworkManager] (pressure pipes).
 *
 * Populated reactively as network segments load (union-find merge, see [ensureRegistered]) rather
 * than from persisted graph data - state is always derived from the segments actually present, so
 * there's nothing to desync from world data. Removal can split a network, which
 * [ensureRegistered]'s cheap merge can't detect; that case is handled by a chunked BFS rebuild
 * spread across [tick] calls (scheduled by [onRemoved]).
 *
 * [isBoundaryEdge] is the one exception to "any two adjacent member positions merge": an edge it
 * flags never merges its two sides, no matter how many other regular connections eventually join
 * them from elsewhere.
 */
abstract class AbstractPipeNetworkManager<N : AbstractPipeNetwork> {
	private val networkOf = HashMap<BlockPos, UUID>()
	private val networks = HashMap<UUID, N>()

	private var rebuildRemaining: MutableSet<BlockPos>? = null
	private val rebuildFrontier = ArrayDeque<BlockPos>()
	private var rebuildCurrentNetwork: N? = null

	/** Builds a fresh, empty network with the given [id] - the one thing a concrete network kind must supply beyond [AbstractPipeNetwork]'s own fields. */
	protected abstract fun createNetwork(id: UUID): N

	/** Whether [pos] is a connectable member of this network kind. */
	protected abstract fun isMember(level: ServerLevel, pos: BlockPos): Boolean

	/** Whether the edge from [pos] toward [direction] must never auto-merge, even though both sides are members. `false` (no boundary concept) by default - see [SubnetBoundary] for the item-pipe case that overrides this. */
	protected open fun isBoundaryEdge(level: ServerLevel, pos: BlockPos, direction: Direction): Boolean = false

	fun networkIdAt(pos: BlockPos): UUID? = networkOf[pos]
	fun network(id: UUID): N? = networks[id]

	/** Every network currently tracked - for a subclass that needs to do its own per-network per-tick work (see [net.kernelpanicsoft.tubularstorage.power.network.PressurePipeNetworkManager.tick]'s equalization pass), not something a plain item-pipe network needs. */
	protected fun allNetworks(): Collection<N> = networks.values

	/** Registers [pos] (assumed to already be a placed/loaded member) into the network graph, merging with any connected neighbors. Idempotent. */
	fun ensureRegistered(level: ServerLevel, pos: BlockPos) {
		if (networkOf.containsKey(pos)) return

		val network = createNetwork(UUID.randomUUID())
		networks[network.id] = network
		network.members += pos
		networkOf[pos] = network.id

		for (direction in Direction.entries) {
			val neighborPos = pos.relative(direction)
			if (!isMember(level, neighborPos)) continue
			if (isBoundaryEdge(level, pos, direction)) continue
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

	/**
	 * Advances the chunked network-split rebuild (if one is pending) by up to
	 * [REBUILD_BUDGET_PER_TICK] positions. Open so a subclass (see
	 * [net.kernelpanicsoft.tubularstorage.power.network.PressurePipeNetworkManager]) can layer its
	 * own per-network per-tick work on top via `super.tick(level)`.
	 *
	 * Respects [isMember]/[isBoundaryEdge] exactly like [ensureRegistered] does when expanding the
	 * frontier, and skips any position [networkOf] already knows about - this runs from
	 * `TickEvent.SERVER_LEVEL_POST` (see `TubularStorage.initCommon`), strictly *after* every loaded
	 * member's own block entity has already ticked (and so already called [ensureRegistered] on
	 * itself, see [net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity.tick]) - so by the
	 * time this runs, [onRemoved]'s own scheduled positions have very often *already* found their
	 * own correct network the ordinary way. Blindly reprocessing them here anyway - as this used to,
	 * ignoring both [networkOf]'s current state and [isBoundaryEdge] entirely, unioning purely by
	 * raw adjacency - would silently stomp that correct, boundary-respecting assignment with a wrong
	 * one: confirmed the hard way, a hook attach/detach several segments away from an internal
	 * boundary elsewhere in the same former network left that boundary's own far side either wrongly
	 * merged back in or stuck on a stale network id, even though the position that actually changed
	 * re-registered correctly on its own the very same tick.
	 */
	open fun tick(level: ServerLevel) {
		val remaining = rebuildRemaining ?: return
		var budget = REBUILD_BUDGET_PER_TICK
		while (budget > 0) {
			if (rebuildFrontier.isEmpty()) {
				var seed = remaining.firstOrNull()
				while (seed != null && (networkOf.containsKey(seed) || !isMember(level, seed))) {
					remaining -= seed
					seed = remaining.firstOrNull()
				}
				if (seed == null) {
					rebuildRemaining = null
					rebuildCurrentNetwork = null
					return
				}
				remaining -= seed
				val network = createNetwork(UUID.randomUUID())
				networks[network.id] = network
				rebuildCurrentNetwork = network
				rebuildFrontier += seed
			}

			val pos = rebuildFrontier.removeFirst()
			if (networkOf.containsKey(pos) || !isMember(level, pos)) continue
			val network = rebuildCurrentNetwork!!
			if (pos in network.members) continue
			network.members += pos
			networkOf[pos] = network.id
			budget--

			for (direction in Direction.entries) {
				val neighborPos = pos.relative(direction)
				if (neighborPos !in remaining) continue
				if (isBoundaryEdge(level, pos, direction)) continue
				rebuildFrontier += neighborPos
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
	}
}
