package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.debug.DebugFlag
import net.kernelpanicsoft.boilerplate.debug.DebugOverlayViewers
import net.minecraft.server.level.ServerLevel

/**
 * Client -> server: which [DebugFlag]s this player now has on.
 *
 * The whole set every time rather than one flip, so a client that reconnects, or one whose earlier
 * packet was lost, says everything it wants in one message and the server's view of it cannot drift
 * - see [DebugOverlayViewers], which keeps only the latest set per player.
 *
 * Keeps every server-side half of the debug tooling - route-search tracing, the pipe and warehouse
 * snapshot broadcasts, and hand-off tracing - active only while somebody is actually asking for
 * that particular one, instead of gating it on the environment.
 */
@Serializable
data class DebugFlagsPacket(val flags: Set<DebugFlag>) {
	fun handleOnServer(context: IPacketContext) {
		DebugOverlayViewers.setViewer(context.player.uuid, flags)
		// A first frame straight away, so turning one on doesn't sit blank for a whole send interval.
		val level = context.player.level() as? ServerLevel ?: return
		if (DebugFlag.NETWORK in flags) DebugNetworkSync.pushNow(level)
		if (DebugFlag.WAREHOUSE in flags) WarehouseDebugSync.pushNow(level)
	}
}
