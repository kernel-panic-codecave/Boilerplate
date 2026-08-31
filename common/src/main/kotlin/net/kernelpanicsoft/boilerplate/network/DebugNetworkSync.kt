package net.kernelpanicsoft.boilerplate.network

import net.kernelpanicsoft.boilerplate.pipe.network.DebugRouteTrace
import net.kernelpanicsoft.boilerplate.pipe.network.PipeNetworkManager
import net.minecraft.server.level.ServerLevel

/**
 * Drains [DebugRouteTrace] and the pipe networks' membership into a
 * [DebugNetworkSnapshotPacket] a few ticks apart, broadcasting it to every player in the level.
 * Wired up from `Boilerplate.init`'s `SERVER_LEVEL_POST` listener and gated internally on
 * [DebugRouteTrace.enabled], so on servers with nobody viewing the overlay this is a single
 * boolean read per tick.
 */
object DebugNetworkSync {
	private const val SEND_INTERVAL_TICKS = 10

	fun tickLevel(level: ServerLevel) {
		if (!DebugRouteTrace.enabled) return
		if (level.players().isEmpty()) return
		if (level.gameTime % SEND_INTERVAL_TICKS != 0L) return
		push(level)
	}

	/** Immediately broadcasts one snapshot, so a player toggling the overlay on doesn't wait a full send interval for the first frame. */
	fun pushNow(level: ServerLevel) {
		if (!DebugRouteTrace.enabled) return
		if (level.players().isEmpty()) return
		push(level)
	}

	private fun push(level: ServerLevel) {

		val networks = PipeNetworkManager.get(level).allNetworks().map { network ->
			DebugNetworkSnapshotPacket.SNetwork(network.id.toString(), network.members.toList())
		}
		if (networks.isEmpty() && DebugRouteTrace.snapshot().isEmpty()) return

		val traces = DebugRouteTrace.snapshot().map { trace ->
			DebugNetworkSnapshotPacket.STrace(
				trace.from,
				trace.edges.map { DebugNetworkSnapshotPacket.SEdge(it.from, it.to, it.kind.ordinal) },
				trace.route,
			)
		}
		BoilerplateNetworkChannel.toPlayersInDimension(level, DebugNetworkSnapshotPacket(networks, traces))
	}
}