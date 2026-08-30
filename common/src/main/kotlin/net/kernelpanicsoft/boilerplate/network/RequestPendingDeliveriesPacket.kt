package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu

/**
 * Client -> server: send back a fresh [PendingDeliveriesPacket] for whichever
 * [AbstractTerminalHookMenu] the requesting player currently has open - polled every quarter
 * second while its own reserved-slot overlays are on screen (see `AbstractTerminalHookScreen`'s
 * own `mainTab`), since a delivery arriving or getting cancelled server-side has no other way to
 * reach the client. No payload; a `data object` (not a plain `object`) since
 * [NetworkChannel][net.kernelpanicsoft.archie.networking.NetworkChannel] requires
 * `KClass.isData`, which a `data object` satisfies same as a data class.
 */
@Serializable
data object RequestPendingDeliveriesPacket {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		menu.sendPendingDeliveries()
	}
}
