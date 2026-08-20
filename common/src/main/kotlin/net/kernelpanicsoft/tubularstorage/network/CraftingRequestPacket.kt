package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.pipe.gui.AbstractTerminalHookMenu

/**
 * Client -> server: request [amount] of [resource] via [net.kernelpanicsoft.tubularstorage.crafting.CraftingRequest]
 * from whichever [AbstractTerminalHookMenu] the requesting player currently has open - an *action*, not
 * state, unlike the search/preview machinery this reuses, so it's the one bespoke packet
 * `docs/design/m4-crafting-automation.md`'s "Terminal" section calls for.
 */
@Serializable
data class CraftingRequestPacket(val resource: SItemResource, val amount: Long) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		menu.submitCraft(resource, amount)
	}
}
