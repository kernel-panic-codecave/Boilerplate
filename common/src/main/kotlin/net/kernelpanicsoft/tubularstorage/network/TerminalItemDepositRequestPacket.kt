package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.pipe.gui.TerminalHookMenu

/** Client -> server: withdraw [stack]'s resource/count from whichever [TerminalHookMenu] the requesting player currently has open. */
@Serializable
data class TerminalItemDepositRequestPacket(val stack: SResourceStack<SItemResource>, val clearCarried: Boolean) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? TerminalHookMenu ?: return
		menu.deposit(stack, clearCarried)
	}
}
