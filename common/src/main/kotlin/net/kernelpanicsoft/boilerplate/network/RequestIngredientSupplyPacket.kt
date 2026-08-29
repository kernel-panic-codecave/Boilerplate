package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTerminalHookMenu

/**
 * Client -> server: for whichever crafting terminal's grid the requesting player currently has
 * open, try to grant each ([Int], [SItemResource]) in [targets] - the exact
 * [net.kernelpanicsoft.boilerplate.pipe.hook.CraftingTerminalHookState.grid] cell a recipe-viewer
 * plugin (`compat/rei`/`compat/jei`/`compat/emi`) worked out that ingredient belongs in - see
 * [CraftingTerminalHookMenu.supplyIngredients]. Fired before delegating to that viewer's own,
 * otherwise unmodified, fill logic, so an ingredient the player doesn't carry but the terminal can
 * reach becomes available - instantly if a provider hook can supply it, for a *second* click
 * otherwise - instead of the viewer just reporting "missing ingredients" forever.
 */
@Serializable
data class RequestIngredientSupplyPacket(val targets: Map<Int, SItemResource>) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? CraftingTerminalHookMenu ?: return
		menu.supplyIngredients(targets)
	}
}
