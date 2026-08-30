package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu
import net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery
import net.minecraft.client.Minecraft

/**
 * Server -> client: this terminal hook's own [PendingDelivery] list right now, oldest first -
 * sent in response to [RequestPendingDeliveriesPacket], the same poll-on-demand pattern
 * [TerminalSearchResultsPacket] uses for search results. Applied to whichever
 * [AbstractTerminalHookMenu] the receiving player currently has open, if any.
 */
@Serializable
data class PendingDeliveriesPacket(val deliveries: List<PendingDelivery>) {
	fun handleOnClient() {
		val menu = Minecraft.getInstance().player?.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		menu.updatePendingDeliveries(deliveries)
	}
}
