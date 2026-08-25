package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.pipe.gui.AbstractTerminalHookMenu

/**
 * Client -> server: re-fetch the distinct set of resources any reachable
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState]'s own patterns can
 * produce - the terminal's Craft tab catalog, independent of current stock. Replies with
 * [CraftableListPacket]. No payload; a `data object`, not a plain `object` - see
 * [RequestTerminalSearchResultsPacket]'s identical note.
 */
@Serializable
data object RequestCraftableListPacket {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		menu.sendCraftableList()
	}
}
