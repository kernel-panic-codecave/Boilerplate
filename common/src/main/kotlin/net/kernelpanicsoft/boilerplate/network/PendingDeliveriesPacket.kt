package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu
import net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery
import net.minecraft.client.Minecraft

/**
 * Server -> client: this terminal hook's own periodic status - its [PendingDelivery] list right
 * now, oldest first, and what its inbox row is actually holding - sent in response to
 * [RequestPendingDeliveriesPacket], the same poll-on-demand pattern [TerminalSearchResultsPacket]
 * uses for search results. Applied to whichever [AbstractTerminalHookMenu] the receiving player
 * currently has open, if any.
 *
 * [inbox] is here because vanilla's own menu sync carries [net.minecraft.world.item.ItemStack]s and
 * nothing else: an item in the inbox arrives on the client for free through the slot it sits in, a
 * fluid or a chemical in the very same row does not exist as far as that machinery is concerned. It
 * rides along with the delivery poll rather than on a channel of its own because it is the same
 * question asked at the same moment - "what is in this terminal right now".
 */
@Serializable
data class PendingDeliveriesPacket(
	val deliveries: List<PendingDelivery>,
	val inbox: List<ResourceStorage.Cell> = emptyList(),
) {
	fun handleOnClient() {
		val menu = Minecraft.getInstance().player?.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		menu.updatePendingDeliveries(deliveries, inbox)
	}
}
