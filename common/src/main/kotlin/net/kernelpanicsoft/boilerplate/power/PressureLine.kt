package net.kernelpanicsoft.boilerplate.power

import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage
import net.kernelpanicsoft.boilerplate.power.network.PressureNetworkBoundary
import net.kernelpanicsoft.boilerplate.power.network.PressurePipeNetworkManager
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * Resolves the "pressure line" a [net.kernelpanicsoft.boilerplate.power.PressureConsumer] draws
 * from - per the architectural decision in `docs/design/m5-pressure-power.md`, there is no
 * network-level energy bucket ([net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage] can't be
 * synthesized as an aggregate), so a consumer's own line is *some* reachable tank/compressor's own
 * real storage instead. Which one specifically doesn't matter: every tank/compressor on the same
 * [net.kernelpanicsoft.boilerplate.power.network.PressurePipeNetwork] is kept near the same
 * fill level by that network's own per-tick equalization pass
 * ([PressurePipeNetworkManager.equalize]), so any one of them is an equally faithful proxy for
 * "the network's pressure."
 */
object PressureLine {
	/**
	 * [from]'s own reachable pressure line, or `null` if neither [from] itself nor any of its
	 * immediate neighbors belongs to a pressure network with a tank/compressor endpoint on it yet.
	 * Reuses [PressurePipeNetworkManager]'s already-cached topology (the same graph the
	 * equalization pass itself walks) rather than running a second, redundant BFS.
	 *
	 * Checks [from]'s own network first, not just its neighbors': since item pipes conduct pressure
	 * too ([PressurePipeNetworkManager.isMember]), [from] is often itself a network member already -
	 * an extraction hook or Crafting CPU leader's own segment position, say. The neighbor check
	 * still matters for a caller that isn't itself a pipe segment at all (the warehouse controller,
	 * sitting next to whatever pipe run reaches it) - but only across an edge
	 * [PressureNetworkBoundary.isBoundaryEdge] itself doesn't flag, the same rule
	 * [PressurePipeNetworkManager]'s own topology merge already applies: a neighbor sitting right on
	 * the far side of an unbridged dedicated-Pressure-Pipe/item-pipe junction (or a
	 * [net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary] one) is deliberately a
	 * *different* network, not a stale one - blindly unioning it back in here would let pressure
	 * "leak" across the exact boundary the topology itself refuses to merge.
	 *
	 * [PressureApi.find] returns the generic CSL `ValueStorage` interface, not the concrete
	 * [ArchieEnergyStorage] [net.kernelpanicsoft.boilerplate.power.PressureConsumer.onPressureTick]
	 * itself requires - every endpoint this mod's own encasements expose really is backed by one
	 * (built via `energyField`), so the cast is safe for them; a hypothetical third-party
	 * `ValueStorage` implementation exposed at the same position would just be skipped instead.
	 */
	fun find(level: ServerLevel, from: BlockPos): ArchieEnergyStorage? {
		val manager = PressurePipeNetworkManager.get(level)
		val networkIds = LinkedHashSet<UUID>()
		manager.networkIdAt(from)?.let { networkIds += it }
		for (direction in Direction.entries) {
			if (PressureNetworkBoundary.isBoundaryEdge(level, from, direction)) continue
			manager.networkIdAt(from.relative(direction))?.let { networkIds += it }
		}

		for (networkId in networkIds) {
			val network = manager.network(networkId) ?: continue
			for (memberPos in network.members) {
				// A tank/compressor encasement *is* a member itself (unlike an item pipe's own
				// endpoints, always a separate non-pipe neighbor) - check the member's own position
				// first, then its neighbors for a hypothetical external (non-`PressurePipeBlock`)
				// energy-exposing block sitting adjacent to the network, symmetric to how item pipes
				// reach an ordinary chest.
				(PressureApi.find(level, memberPos, Direction.NORTH) as? ArchieEnergyStorage)?.let { return it }
				for (probeDirection in Direction.entries) {
					val storage = PressureApi.find(level, memberPos.relative(probeDirection), probeDirection.opposite) as? ArchieEnergyStorage ?: continue
					return storage
				}
			}
		}
		return null
	}
}
