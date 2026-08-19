package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.pipe.gui.PatternTerminalHookMenu

/** Client -> server: overwrites the ghost output with [resource] at [amount] (or clears it, for [earth.terrarium.common_storage_lib.resources.item.ItemResource.BLANK]) on whichever [PatternTerminalHookMenu] the requesting player currently has open. */
@Serializable
data class SetPatternGhostOutputPacket(val resource: SItemResource, val amount: Long) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? PatternTerminalHookMenu ?: return
		menu.applyGhostOutput(resource, amount)
	}
}
