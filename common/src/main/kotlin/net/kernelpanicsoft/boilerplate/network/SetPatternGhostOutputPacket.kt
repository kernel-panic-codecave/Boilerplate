package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.PatternTerminalHookMenu

/** Client -> server: overwrites ghost output [index] with [resource] at [amount] (or clears it, for [earth.terrarium.common_storage_lib.resources.item.ItemResource.BLANK]) on whichever [PatternTerminalHookMenu] the requesting player currently has open - only meaningful in [net.kernelpanicsoft.boilerplate.crafting.PatternKind.PROCESSING], up to 9 of these. */
@Serializable
data class SetPatternGhostOutputPacket(val index: Int, val resource: SItemResource, val amount: Long) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? PatternTerminalHookMenu ?: return
		menu.applyGhostOutput(index, resource, amount)
	}
}
