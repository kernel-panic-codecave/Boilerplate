package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu
import net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery

/**
 * Client -> server: the player clicked a reserved-slot placeholder for [PendingDelivery.id],
 * asking to cancel it. Removing it from the owning `TerminalHookState.pendingDeliveries` is the
 * whole of the cancellation itself - the in-flight `TravelingItem`/gantry job this delivery
 * dispatched keeps running, but [net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity.tick]
 * finds no matching reservation once it actually arrives and redirects the real item back into
 * the network instead, exactly as if this terminal had refused it. A no-op if [id] no longer
 * names a pending delivery at all - already delivered, already cancelled, or never this player's
 * own menu's to cancel.
 */
@Serializable
data class CancelPendingDeliveryPacket(val id: Long) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		menu.cancelDelivery(id)
	}
}
