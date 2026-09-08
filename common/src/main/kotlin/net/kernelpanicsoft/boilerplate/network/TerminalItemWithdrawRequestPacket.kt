package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.ResourceComponent

/** Client -> server: withdraw [stack]'s resource/count from whichever [AbstractTerminalHookMenu] the requesting player currently has open. */
@Serializable
data class TerminalItemWithdrawRequestPacket(val stack: SResourceStack<*>) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		@Suppress("UNCHECKED_CAST")
		menu.withdraw(stack as ResourceStack<ResourceComponent>)
	}
}
