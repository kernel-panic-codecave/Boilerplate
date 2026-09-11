package net.kernelpanicsoft.boilerplate.compat.jei

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import mezz.jei.api.neoforge.NeoForgeTypes
import net.neoforged.neoforge.fluids.FluidStack

/**
 * Teaches JEI how to show a **fluid** row and how to read one back, from the one module that can
 * name what JEI wants one to be: NeoForge's own [FluidStack].
 *
 * The whole reason [JeiResourceStacks] has a loader seam at all - see there. Called from this
 * loader's own client setup, so nothing here runs on a dedicated server or without JEI present.
 */
fun registerNeoForgeJeiResourceStacks() {
	JeiResourceStacks.register("fluid") { resource, amount ->
		val fluid = (resource as? FluidResource)?.takeIf { !it.isBlank } ?: return@register null
		JeiIngredient(NeoForgeTypes.FLUID_STACK, FluidStack(fluid.type.builtInRegistryHolder(), amount.toInt(), fluid.dataPatch))
	}

	JeiResourceParsers.register("fluid") { ingredient ->
		val stack = ingredient.getIngredient(NeoForgeTypes.FLUID_STACK).orElse(null)?.takeUnless { it.isEmpty } ?: return@register null
		ResourceStack(FluidResource.of(stack.fluid, stack.componentsPatch), stack.amount.toLong().coerceAtLeast(1L))
	}
}
