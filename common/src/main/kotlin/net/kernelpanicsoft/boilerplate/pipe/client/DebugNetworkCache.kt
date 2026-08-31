package net.kernelpanicsoft.boilerplate.pipe.client

import net.kernelpanicsoft.boilerplate.network.DebugNetworkSnapshotPacket
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth

/**
 * Client-side mirror of the server's [DebugNetworkSnapshotPacket], keyed to the client level it
 * was received for so a dimension switch (or world load) never draws the previous world's pipes
 * over the new one. Updated from the network thread, read from the render thread, so all state is
 * [Volatile] and entirely copy-on-write.
 */
object DebugNetworkCache {
	data class Network(val id: String, val members: List<BlockPos>, val color: Int)

	data class Trace(val from: BlockPos, val edges: List<Edge>, val route: List<BlockPos>)

	data class Edge(val from: BlockPos, val to: BlockPos, val kind: Int)

	@Volatile
	private var networks: List<Network> = emptyList()

	@Volatile
	private var traces: List<Trace> = emptyList()

	@Volatile
	private var level: ClientLevel? = null

	fun update(packet: DebugNetworkSnapshotPacket) {
		networks = packet.networks.map { Network(it.id, it.members, colorFor(it.id)) }
		traces = packet.traces.map { Trace(it.from, it.edges.map { e -> Edge(e.from, e.to, e.kind) }, it.route) }
	}

	fun clear() {
		networks = emptyList()
		traces = emptyList()
	}

	/** Drops the cached snapshot whenever the client's current level changes - the first call for a fresh level is the reset. */
	fun onLevel(level: ClientLevel?) {
		if (level !== this.level) {
			this.level = level
			if (level != null) clear()
		}
	}

	fun snapshot(): Pair<List<Network>, List<Trace>> = networks to traces

	/** A stable per-network color derived from the network's id, so every node of one network renders the same hue. */
	private fun colorFor(id: String): Int {
		val hue = (id.hashCode() and 0x7fffffff) % 360 / 360f
		return Mth.hsvToRgb(hue, 0.85f, 1f)
	}
}