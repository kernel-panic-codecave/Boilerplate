package net.kernelpanicsoft.boilerplate.pipe.network

import net.kernelpanicsoft.boilerplate.debug.DebugFlag
import net.kernelpanicsoft.boilerplate.debug.DebugOverlayViewers
import net.minecraft.core.BlockPos

/**
 * How the route search treated one hop between two positions - what [TraceEdge]s the in-world
 * debug overlay (see [net.kernelpanicsoft.boilerplate.pipe.client.DebugNetworkRenderer]) should
 * draw for it.
 */
enum class EdgeKind {
	/** Ordinary pipe-to-pipe walk the BFS crossed and queued further. */
	TRANSIT,

	/** A [SubnetBoundary] edge - the search stops here and never inspects the far network. */
	BOUNDARY,

	/** An accepting destination the search probed and could have won. */
	CANDIDATE,

	/** Something that rejected the item (no storage, can't insert, filter/color/terminal rules). */
	REJECTED,
}

/** One directed hop the route search considered, from [from] toward [to]. */
data class TraceEdge(val from: BlockPos, val to: BlockPos, val kind: EdgeKind)

/**
 * Everything one [PipeRouter.findRoute] call explored: every [TraceEdge] the BFS considered plus
 * the [route] it settled on (empty when nothing accepted).
 */
class RouteSearchTrace(val from: BlockPos) {
	val edges = ArrayList<TraceEdge>()
	var route: List<BlockPos> = emptyList()
		internal set

	/** Records [edge] unless this trace already hit its per-trace edge cap. */
	fun record(edge: TraceEdge) {
		if (edges.size >= MAX_EDGES) return
		edges += edge
	}

	companion object {
		private const val MAX_EDGES = 4096
	}
}

/**
 * Server-side collector behind the route-search overlay. [PipeRouter.findRoute] starts a
 * trace for every search it runs while [enabled], [step] records each explored direction, and the
 * per-tick snapshot builder ([net.kernelpanicsoft.boilerplate.network.DebugNetworkSync]) ships
 * the recent traces to connected clients. Only positions ever see the funnel, so the entire
 * mechanism is a pair of null-checks per explored direction on servers with nobody viewing.
 */
object DebugRouteTrace {
	private const val TRACE_CAP = 64

	private val recent = ArrayList<RouteSearchTrace>()

	/** Whether route-search tracing is active at all - [DebugFlag.NETWORK]'s own gate, since tracing is only ever wanted while somebody is looking at the overlay that draws it. */
	val enabled: Boolean get() = DebugOverlayViewers.enabled(DebugFlag.NETWORK)

	/** Starts a fresh trace for a search rooted at [from], bounding the kept history to the most recent [TRACE_CAP] searches. */
	fun startSearch(from: BlockPos): RouteSearchTrace {
		val trace = RouteSearchTrace(from)
		recent += trace
		while (recent.size > TRACE_CAP) recent.removeAt(0)
		return trace
	}

	/** A copy of the recently recorded traces, for the snapshot builder. */
	fun snapshot(): List<RouteSearchTrace> = recent.toList()

	fun clear() {
		recent.clear()
	}
}