package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.pipe.gui.WarehouseTerminalMenu

/** Client -> server: re-run the search behind whichever [WarehouseTerminalMenu] the requesting player currently has open, and send back a fresh [WarehouseSearchResultsPacket] - a manual refresh, since results otherwise only update on open/withdrawal. No payload; a `data object` (not a plain `object`) since [NetworkChannel][net.kernelpanicsoft.archie.networking.NetworkChannel] requires `KClass.isData`, which a `data object` satisfies same as a data class. */
@Serializable
data object RequestWarehouseSearchResultsPacket {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? WarehouseTerminalMenu ?: return
		menu.sendSearchResults()
	}
}
