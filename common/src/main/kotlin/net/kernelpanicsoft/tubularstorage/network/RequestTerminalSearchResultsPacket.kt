package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.pipe.gui.AbstractTerminalHookMenu

/** Client -> server: re-run the search behind whichever [AbstractTerminalHookMenu] the requesting player currently has open, and send back a fresh [TerminalSearchResultsPacket] - a manual refresh, since results otherwise only update on open/withdrawal. No payload; a `data object` (not a plain `object`) since [NetworkChannel][net.kernelpanicsoft.archie.networking.NetworkChannel] requires `KClass.isData`, which a `data object` satisfies same as a data class. */
@Serializable
data object RequestTerminalSearchResultsPacket {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		menu.sendSearchResults()
	}
}
