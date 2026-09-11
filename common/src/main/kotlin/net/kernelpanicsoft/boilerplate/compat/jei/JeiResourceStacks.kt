package net.kernelpanicsoft.boilerplate.compat.jei

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import mezz.jei.api.gui.builder.IClickableIngredientFactory
import mezz.jei.api.ingredients.ITypedIngredient
import mezz.jei.api.ingredients.IIngredientType
import mezz.jei.api.runtime.IClickableIngredient
import mezz.jei.api.constants.VanillaTypes
import net.kernelpanicsoft.boilerplate.compat.ViewerResourceParsers
import net.kernelpanicsoft.boilerplate.compat.ViewerResourceStacks
import net.minecraft.client.renderer.Rect2i
import java.util.Optional

/**
 * One ingredient as JEI wants it: a value together with the [IIngredientType] it belongs to.
 *
 * JEI has no single neutral ingredient object the way EMI and REI do - `IClickableIngredientFactory`
 * takes a `(type, value)` pair - and the pair has to travel together because only the registering
 * side knows both. Wrapping them keeps [JeiResourceStacks] a plain
 * [ViewerResourceStacks] like the other two rather than something JEI-shaped.
 */
class JeiIngredient<T>(private val type: IIngredientType<T>, private val value: T) {
	/** This ingredient as a clickable one covering [area] - what a GUI handler hands back to JEI. */
	fun clickableIn(factory: IClickableIngredientFactory, area: Rect2i): Optional<out IClickableIngredient<*>> =
		factory.createBuilder(type, value).buildWithArea(area)
}

/**
 * How each resource kind is shown to JEI - see [ViewerResourceStacks], and the EMI and REI plugins'
 * own twins.
 *
 * Only the item kind can be registered here. JEI's fluid ingredient type is **loader-specific**
 * (`mezz.jei.api.fabric.ingredients.fluids` on one side, NeoForge's own `FluidStack` on the other),
 * so `common` cannot name it - which is why this had no fluid rows at all while EMI and REI did.
 * Each loader module registers its own through [register], from its own client setup, and every
 * further kind plugs in the same way from wherever it can name what JEI wants.
 */
val JeiResourceStacks = ViewerResourceStacks<JeiIngredient<*>>().apply {
	register("item") { resource, amount ->
		(resource as? ItemResource)?.takeIf { !it.isBlank }
			?.let { JeiIngredient(VanillaTypes.ITEM_STACK, it.toStack(amount.toInt().coerceAtLeast(1))) }
	}
}

/**
 * How each resource kind is read back *out* of JEI - the mirror of [JeiResourceStacks], and what
 * lets a pattern be authored from a recipe naming something other than an item.
 *
 * Split across the loaders for exactly the reason [JeiResourceStacks] is: JEI's fluid ingredient
 * type cannot be named from `common`, so each loader registers its own parser next to its own
 * converter, and every further kind plugs in the same way.
 */
val JeiResourceParsers = ViewerResourceParsers<ITypedIngredient<*>>().apply {
	register("item") { ingredient ->
		ingredient.getIngredient(VanillaTypes.ITEM_STACK).orElse(null)
			?.takeUnless { it.isEmpty }
			?.let { ResourceStack(ItemResource.of(it), it.count.toLong()) }
	}
}
