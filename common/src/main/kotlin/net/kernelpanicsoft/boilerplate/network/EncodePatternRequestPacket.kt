package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.PatternTerminalHookMenu

/** Client -> server: try [PatternTerminalHookMenu.encode]ing whichever pattern terminal's own ghost grid the requesting player currently has open. No payload; a `data object`, matching [CraftGridRequestPacket]'s own convention. */
@Serializable
data object EncodePatternRequestPacket {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? PatternTerminalHookMenu ?: return
		menu.encode()
	}
}
