package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.StockingRowMenu
import net.kernelpanicsoft.boilerplate.resource.SResourceComponent

/**
 * Client -> server: overwrites column [index] of the open hook's own
 * [net.kernelpanicsoft.boilerplate.pipe.hook.StockingRow] with [resource] at [amount] (or clears it,
 * for a blank resource).
 *
 * One packet for both hooks that carry such a row - a requester and an interface configure the same
 * thing, so they take the same edit. The menu is addressed through [StockingRowMenu] rather than by
 * concrete type for exactly that reason.
 */
@Serializable
data class SetStockingTargetPacket(val index: Int, val resource: SResourceComponent, val amount: Long) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? StockingRowMenu ?: return
		menu.applyStockingTarget(index, resource, amount)
	}
}
