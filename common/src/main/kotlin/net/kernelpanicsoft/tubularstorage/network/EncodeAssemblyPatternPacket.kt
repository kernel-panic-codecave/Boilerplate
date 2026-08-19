package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.crafting.gui.AssemblyTableMenu

/** Client -> server: snapshot whichever [AssemblyTableMenu] the requesting player currently has open's grid/output into a new pattern - see [AssemblyTableMenu.encode]. No payload; a `data object` for the same reason as [RequestTerminalSearchResultsPacket]. */
@Serializable
data object EncodeAssemblyPatternPacket {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? AssemblyTableMenu ?: return
		menu.encode()
	}
}
