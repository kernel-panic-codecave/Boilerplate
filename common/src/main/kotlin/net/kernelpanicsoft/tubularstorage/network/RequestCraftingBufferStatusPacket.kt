package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.crafting.gui.CraftingBufferMenu

/** Client -> server: asks for the current job/backlog status of whichever [CraftingBufferMenu] the requesting player currently has open - see [CraftingBufferStatusPacket]. No payload, matching [RequestCraftJobTreePacket]'s own convention. */
@Serializable
data object RequestCraftingBufferStatusPacket {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? CraftingBufferMenu ?: return
		menu.sendStatus()
	}
}
