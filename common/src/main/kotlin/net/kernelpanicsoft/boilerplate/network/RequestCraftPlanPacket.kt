package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftPlanMenu
import net.kernelpanicsoft.boilerplate.network.SResourceComponent

/**
 * Client -> server: what crafting [amount] of [resource] would entail, and where it could run.
 * A dry run - nothing is requested. Replies with [CraftPlanPacket].
 */
@Serializable
data class RequestCraftPlanPacket(val resource: SResourceComponent, val amount: Long) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? CraftPlanMenu ?: return
		menu.sendCraftPlan(resource, amount)
	}
}
