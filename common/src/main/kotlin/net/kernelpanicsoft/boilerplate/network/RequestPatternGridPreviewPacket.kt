package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTerminalHookMenu
import net.kernelpanicsoft.boilerplate.pipe.gui.PatternTerminalHookMenu

/** Client -> server: asks for the current live preview of whichever crafting terminal's own grid the requesting player currently has open - see [CraftGridPreviewPacket]. No payload; a `data object`, matching [RequestTerminalSearchResultsPacket]'s own convention. */
@Serializable
data object RequestPatternGridPreviewPacket {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? PatternTerminalHookMenu ?: return
		menu.sendGridPreview()
	}
}
