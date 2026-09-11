package net.kernelpanicsoft.boilerplate.compat.mekanism

import dev.architectury.platform.Platform
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import mekanism.api.chemical.Chemical
import mekanism.api.chemical.ChemicalStack
import mekanism.client.recipe_viewer.emi.ChemicalEmiStack
import mekanism.client.recipe_viewer.jei.MekanismJEI
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.boilerplate.compat.emi.EmiResourceParsers
import net.kernelpanicsoft.boilerplate.compat.emi.EmiResourceStacks
import net.kernelpanicsoft.boilerplate.compat.jei.JeiIngredient
import net.kernelpanicsoft.boilerplate.compat.jei.JeiResourceParsers
import net.kernelpanicsoft.boilerplate.compat.jei.JeiResourceStacks

/**
 * Teaches the recipe viewers how to show a **chemical** row, and how to read one back, so "view
 * recipes" works on one exactly as it does on an item or a fluid - and so a pattern authored from a
 * chemical recipe names the chemical rather than skipping the cell.
 *
 * Lives here because a viewer's chemical ingredient is Mekanism's own type - `ChemicalEmiStack` for
 * EMI, [MekanismJEI.TYPE_CHEMICAL] for JEI - and both are in Mekanism's *client* code rather than
 * its `:api` artifact. This module compiles against the whole mod, so it can name them; nothing in
 * `common` can. That is the same seam JEI's own fluid type needs, used for the same reason - see
 * [net.kernelpanicsoft.boilerplate.compat.jei.JeiResourceStacks].
 *
 * **No REI converter**, and not for want of trying: Mekanism ships JEI and EMI plugins and no REI
 * one, so there is no chemical entry type for REI to be told about. A chemical row is simply not
 * lookupable there until Mekanism has one.
 *
 * Client-only, and called from this loader's own client setup once Mekanism is known to be present.
 * Each viewer is checked separately because a pack may have any one of them, or none.
 */
fun registerMekanismResourceStacks() {
	if (Platform.isModLoaded("emi")) {
		EmiResourceStacks.register("chemical") { resource, amount ->
			chemicalStack(resource, amount)?.let(ChemicalEmiStack::create)
		}
		EmiResourceParsers.register("chemical") { stack ->
			val chemical = stack.getKeyOfType(Chemical::class.java)?.takeUnless { it.isEmptyType } ?: return@register null
			ResourceStack(ChemicalResource.of(chemical), stack.amount.coerceAtLeast(1L))
		}
	}
	if (Platform.isModLoaded("jei")) {
		JeiResourceStacks.register("chemical") { resource, amount ->
			chemicalStack(resource, amount)?.let { JeiIngredient(MekanismJEI.TYPE_CHEMICAL, it) }
		}
		JeiResourceParsers.register("chemical") { ingredient ->
			val stack = ingredient.getIngredient(MekanismJEI.TYPE_CHEMICAL).orElse(null)?.takeUnless { it.isEmpty } ?: return@register null
			ResourceStack(ChemicalResource.of(stack), stack.amount.coerceAtLeast(1L))
		}
	}
}

/**
 * [amount] of [resource] as Mekanism's own stack type, or `null` if it is not a chemical or is a
 * blank one.
 *
 * The kind check is belt and braces - [net.kernelpanicsoft.boilerplate.compat.ViewerResourceStacks]
 * only ever calls a converter for its own kind - but the *blank* check is not: a viewer handed an
 * empty ingredient renders a hole rather than nothing, and a blank resource reaching here at all
 * means something upstream is listing an empty row.
 */
private fun chemicalStack(resource: ResourceComponent, amount: Long): ChemicalStack? =
	(resource as? ChemicalResource)?.takeIf { !it.isBlank }?.toStack(amount)
