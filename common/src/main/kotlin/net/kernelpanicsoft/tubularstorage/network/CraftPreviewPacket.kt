package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.tubularstorage.pipe.gui.TerminalHookMenu
import net.minecraft.client.Minecraft

/** Server -> client: reply to [RequestCraftPreviewPacket] - [resource] is currently craftable up to [maxCraftable]. Applied to whichever [TerminalHookMenu] the receiving player currently has open, if any. */
@Serializable
data class CraftPreviewPacket(val resource: SItemResource, val maxCraftable: Long) {
	fun handleOnClient() {
		val menu = Minecraft.getInstance().player?.containerMenu as? TerminalHookMenu ?: return
		menu.updateCraftPreview(resource, maxCraftable)
	}
}
