package net.kernelpanicsoft.boilerplate.compat.jei

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import mezz.jei.api.fabric.constants.FabricTypes
import mezz.jei.api.fabric.ingredients.fluids.JeiFluidIngredient
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant

/**
 * Teaches JEI how to show a **fluid** row and how to read one back, from the one module that can
 * name what JEI wants one to be: Fabric's own `IJeiFluidIngredient` over a `FluidVariant`.
 *
 * The whole reason [JeiResourceStacks] has a loader seam at all - see there. Called from this
 * loader's own client setup, so nothing here runs on a dedicated server or without JEI present.
 */
fun registerFabricJeiResourceStacks() {
	JeiResourceStacks.register("fluid") { resource, amount ->
		val fluid = (resource as? FluidResource)?.takeIf { !it.isBlank } ?: return@register null
		JeiIngredient(FabricTypes.FLUID_STACK, JeiFluidIngredient(FluidVariant.of(fluid.type, fluid.dataPatch), amount))
	}

	JeiResourceParsers.register("fluid") { ingredient ->
		val fluid = ingredient.getIngredient(FabricTypes.FLUID_STACK).orElse(null) ?: return@register null
		val variant = fluid.fluidVariant.takeUnless { it.isBlank } ?: return@register null
		ResourceStack(FluidResource.of(variant.fluid, variant.components), fluid.amount.coerceAtLeast(1L))
	}
}
