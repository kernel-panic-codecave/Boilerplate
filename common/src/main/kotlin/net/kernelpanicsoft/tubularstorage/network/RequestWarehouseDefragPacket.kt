package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.pipe.gui.TerminalHookMenu

/** Client -> server: defrag every warehouse reachable from whichever [TerminalHookMenu] the requesting player currently has open - see [TerminalHookMenu.requestDefrag]. No payload; a `data object` for the same reason as [RequestTerminalSearchResultsPacket]. */
@Serializable
data object RequestWarehouseDefragPacket {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? TerminalHookMenu ?: return
		menu.requestDefrag()
	}
}
