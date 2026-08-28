package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu
import net.minecraft.client.Minecraft

/**
 * Server -> client: reply to [RequestCraftableListPacket] - [resources] is the distinct set of
 * items currently craftable somewhere reachable, regardless of current stock. Applied to whichever
 * [AbstractTerminalHookMenu] the receiving player currently has open, if any.
 */
@Serializable
data class CraftableListPacket(val resources: List<SItemResource>) {
	fun handleOnClient() {
		val menu = Minecraft.getInstance().player?.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		menu.updateCraftableList(resources)
	}
}
