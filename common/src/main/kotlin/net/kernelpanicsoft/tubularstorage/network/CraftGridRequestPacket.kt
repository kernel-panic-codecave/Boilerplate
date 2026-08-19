package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.pipe.gui.CraftingTerminalHookMenu

/** Client -> server: try assembling whichever crafting terminal's own grid the requesting player currently has open - see [CraftingTerminalHookMenu.craftGrid]. No payload; a `data object`, matching [RequestTerminalSearchResultsPacket]'s own convention. */
@Serializable
data object CraftGridRequestPacket {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? CraftingTerminalHookMenu ?: return
		menu.craftGrid()
	}
}
