package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.network.DebugRouteTrace
import net.minecraft.server.level.ServerLevel

/**
 * Client -> server: the player turned the F3+B route-search overlay on or off. This keeps the
 * server-side tracing and snapshot broadcasting (see [DebugRouteTrace]/[DebugNetworkSync]) active
 * only while at least one connected player is actually looking, instead of gating it on the
 * environment. Sent on every state flip of the vanilla hitbox toggle.
 */
@Serializable
data class DebugOverlayTogglePacket(val on: Boolean) {
	fun handleOnServer(context: IPacketContext) {
		DebugRouteTrace.setViewer(context.player.uuid, on)
		if (on) {
			val level = context.player.level() as? ServerLevel ?: return
			DebugNetworkSync.pushNow(level)
		}
	}
}