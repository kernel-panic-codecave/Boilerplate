package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu
import net.kernelpanicsoft.boilerplate.pipe.gui.DrainResult
import net.minecraft.network.chat.Component
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.resource.SResourceStack

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
		if (drainContainer) {
			when (menu.depositCarriedContainer()) {
				// Drained, or a container the network currently has nowhere to send: either way the
				// gesture landed on what it was aimed at, and storing the container instead would be
				// the opposite of what was asked. A full tank that goes into the network because
				// nothing could route its chemical is a bad enough surprise to be worth this
				// distinction existing at all.
				is DrainResult.Drained -> return
				// Says so, rather than doing nothing visible. Left silent, a container the network
				// cannot currently route reads as the gesture being broken - and it is usually
				// transient (a staging buffer full, a destination briefly gone), so the same click a
				// minute later works and there is nothing to tell the two apart by.
				DrainResult.NothingRoutable -> {
					context.player.displayClientMessage(
						Component.literal("Nothing in that container has anywhere to go right now"),
						true,
					)
					return
				}
				// Not a container of any registered kind - the gesture missed, so store the item, as
				// a plain click would have.
				DrainResult.NotAContainer -> {}
			}
		}
		@Suppress("UNCHECKED_CAST")
		menu.deposit(stack as ResourceStack<ResourceComponent>, clearCarried, clearSlot)
	}
}
