package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.crafting.gui.CraftingBufferMenu

/** Client -> server: cancels [jobId]'s own job on whichever [CraftingBufferMenu] the requesting player currently has open - see [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementState.cancelJob]. */
@Serializable
data class CancelCraftingBufferJobPacket(val jobId: String) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? CraftingBufferMenu ?: return
		menu.cancelJob(jobId)
	}
}
