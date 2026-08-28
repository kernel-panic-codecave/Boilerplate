package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.PatternTerminalHookMenu

/** Client -> server: overwrites ghost input [index] with [resource] (or clears it, for [earth.terrarium.common_storage_lib.resources.item.ItemResource.BLANK]) on whichever [PatternTerminalHookMenu] the requesting player currently has open - see [net.kernelpanicsoft.boilerplate.pipe.gui.GhostSlot]'s own left-click handling. */
@Serializable
data class SetPatternGhostInputPacket(val index: Int, val resource: SItemResource) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? PatternTerminalHookMenu ?: return
		menu.applyGhostInput(index, resource)
	}
}
