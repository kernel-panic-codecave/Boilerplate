package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.debug.DebugOverlayViewers
import net.minecraft.server.level.ServerLevel

/**
 * Client -> server: the player turned the F3+B debug overlay on or off. This keeps every
 * server-side half of it - route-search tracing plus the pipe and warehouse snapshot broadcasts
 * (see [DebugOverlayViewers], [DebugNetworkSync] and [WarehouseDebugSync]) - active only while at
 * least one connected player is actually looking, instead of gating it on the environment. Sent on
 * every state flip of the vanilla hitbox toggle.
 */
@Serializable
data class DebugOverlayTogglePacket(val on: Boolean) {
	fun handleOnServer(context: IPacketContext) {
		DebugOverlayViewers.setViewer(context.player.uuid, on)
		if (on) {
			val level = context.player.level() as? ServerLevel ?: return
			DebugNetworkSync.pushNow(level)
			WarehouseDebugSync.pushNow(level)
		}
	}
}