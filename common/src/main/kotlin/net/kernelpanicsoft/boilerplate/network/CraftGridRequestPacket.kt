package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTerminalHookMenu

/**
 * Client -> server: try assembling whichever crafting terminal's own grid the requesting player
 * currently has open - the same click ([shiftClick] `false`, once) / shift-click ([shiftClick]
 * `true`, repeatedly until ingredients or inventory space run out) a real vanilla crafting table's
 * own result slot offers - see [CraftingTerminalHookMenu.craftOnce].
 */
@Serializable
data class CraftGridRequestPacket(val shiftClick: Boolean) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? CraftingTerminalHookMenu ?: return
		menu.craftOnce(shiftClick)
	}
}
