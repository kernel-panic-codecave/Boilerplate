package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.ResourceComponent

/**
 * Client -> server: put [stack]'s resource/count into whichever [AbstractTerminalHookMenu] the
 * requesting player currently has open.
 *
 * [drainContainer] asks for the other thing a carried stack can mean: not "store this item" but
 * "empty what is inside it into the network", which is what a player holding a full bucket over the
 * terminal view means when they hold ctrl. The two cannot be told apart from the stack alone - a
 * water bucket is a perfectly good thing to want stored as an item - so the intent travels with the
 * request rather than being guessed at the far end.
 */
@Serializable
data class TerminalItemDepositRequestPacket(
	val stack: SResourceStack<*>,
	val clearCarried: Boolean,
	val clearSlot: Int?,
	val drainContainer: Boolean = false,
) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		// Falls through to an ordinary deposit when the carried stack turns out not to be a container
		// of any registered kind, so a mistaken ctrl-click stores the item rather than doing nothing.
		if (drainContainer && menu.depositCarriedContainer()) return
		@Suppress("UNCHECKED_CAST")
		menu.deposit(stack as ResourceStack<ResourceComponent>, clearCarried, clearSlot)
	}
}
