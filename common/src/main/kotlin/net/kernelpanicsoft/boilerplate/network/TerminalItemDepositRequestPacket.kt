package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu

/** Client -> server: withdraw [stack]'s resource/count from whichever [AbstractTerminalHookMenu] the requesting player currently has open. */
@Serializable
data class TerminalItemDepositRequestPacket(val stack: SResourceStack<SItemResource>, val clearCarried: Boolean, val clearSlot: Int?) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		menu.deposit(stack, clearCarried, clearSlot)
	}
}
