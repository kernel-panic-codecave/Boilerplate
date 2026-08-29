package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTerminalHookMenu

/**
 * Client -> server: for whichever crafting terminal's grid the requesting player currently has
 * open, try to put one of each of [resources] into their own inventory - see
 * [CraftingTerminalHookMenu.supplyIngredients]. Fired by a recipe viewer's own "transfer recipe"
 * click (`compat/rei`/`compat/jei`/`compat/emi`) before delegating to that viewer's own, otherwise
 * unmodified, player-inventory-based fill logic, so an ingredient the player doesn't carry but the
 * terminal can reach becomes available for a *second* click instead of the viewer just reporting
 * "missing ingredients" forever.
 */
@Serializable
data class RequestIngredientSupplyPacket(val resources: List<SItemResource>) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? CraftingTerminalHookMenu ?: return
		menu.supplyIngredients(resources)
	}
}
