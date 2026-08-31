package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.pipe.client.DebugNetworkCache

/**
 * Server -> client sync of the route-search overlay: every pipe network's membership (so
 * the client can draw one colored box per node) plus the recently recorded
 * [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter.findRoute] search traces (so it can
 * draw every path an extract actually checked). Broadcast periodically rather than per-tick - the
 * overlay is diagnostic, missing a frame costs nothing. See
 * [net.kernelpanicsoft.boilerplate.pipe.network.DebugRouteTrace].
 */
@Serializable
data class DebugNetworkSnapshotPacket(
	val networks: List<SNetwork> = emptyList(),
	val traces: List<STrace> = emptyList(),
) {
	fun handleOnClient() {
		DebugNetworkCache.update(this)
	}

	/** One pipe network: its stable [id] and every member position it spans. */
	@Serializable
	data class SNetwork(val id: String, val members: List<SBlockPos>)

	/** One [net.kernelpanicsoft.boilerplate.pipe.network.RouteSearchTrace]: the search [from] position, every hop it explored, and the route it settled on. */
	@Serializable
	data class STrace(val from: SBlockPos, val edges: List<SEdge>, val route: List<SBlockPos>)

	/** One directed hop, [kind] being the ordinal of [net.kernelpanicsoft.boilerplate.pipe.network.EdgeKind]. */
	@Serializable
	data class SEdge(val from: SBlockPos, val to: SBlockPos, val kind: Int)
}