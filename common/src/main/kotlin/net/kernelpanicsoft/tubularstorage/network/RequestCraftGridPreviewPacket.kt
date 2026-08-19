package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.pipe.gui.CraftingTerminalHookMenu

/** Client -> server: asks for the current live preview of whichever crafting terminal's own grid the requesting player currently has open - see [CraftGridPreviewPacket]. No payload; a `data object`, matching [RequestTerminalSearchResultsPacket]'s own convention. */
@Serializable
data object RequestCraftGridPreviewPacket {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? CraftingTerminalHookMenu ?: return
		menu.sendGridPreview()
	}
}
