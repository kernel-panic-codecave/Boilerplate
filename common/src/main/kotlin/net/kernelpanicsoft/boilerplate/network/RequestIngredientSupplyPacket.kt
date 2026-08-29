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
 *
 * [amount] mirrors each recipe-viewer's own per-target quantity signal - EMI's own
 * `EmiCraftContext.amount` is the exact one: `1` for a plain fill, `Int.MAX_VALUE` for a
 * shift-click ("fill with as much as possible"), but also whatever *specific* count EMI's own BOM
 * sidebar fill (recipe-tree mode) asks for when it fills the crafting table for one particular step
 * of a larger tree - genuinely not always 1 or "as much as possible". REI/JEI only ever offer a
 * boolean "shift-click fills as much as possible" signal (`TransferHandler.Context.isStackedCrafting()`,
 * `maxTransfer`), so their own callers just pass `1` or `Int.MAX_VALUE.toLong()` - the same sentinel
 * EMI itself uses for "as much as possible", which [CraftingTerminalHookMenu.supplyIngredients]
 * caps at each resource's own max stack size regardless of caller, so a nonsensically large value
 * never gets requested literally.
 */
@Serializable
data class RequestIngredientSupplyPacket(val targets: Map<Int, SItemResource>, val amount: Long = 1L) {
	fun handleOnServer(context: IPacketContext) {
		val menu = context.player.containerMenu as? CraftingTerminalHookMenu ?: return
		menu.supplyIngredients(targets, amount)
	}
}
