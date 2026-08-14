package net.kernelpanicsoft.tubularstorage.network

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.archie.serialization.serializers.SItemStack
import net.kernelpanicsoft.tubularstorage.pipe.gui.WarehouseTerminalMenu

/** Client -> server: withdraw [stack]'s resource/count from whichever [WarehouseTerminalMenu] the requesting player currently has open. */
@Serializable
data class WithdrawFromWarehousePacket(val stack: SItemStack) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? WarehouseTerminalMenu ?: return
		menu.withdraw(ItemResource.of(stack), stack.count.toLong())
	}
}
