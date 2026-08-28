package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftTreeMenu

/** Client -> server: asks for the current job tree of whichever terminal-family menu ([CraftTreeMenu]) the requesting player currently has open - see [CraftJobTreePacket]. No payload; a `data object`, matching [RequestTerminalSearchResultsPacket]'s own convention. */
@Serializable
data object RequestCraftJobTreePacket {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? CraftTreeMenu ?: return
		menu.sendCraftTree()
	}
}
