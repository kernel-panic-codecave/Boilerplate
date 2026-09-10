package net.kernelpanicsoft.boilerplate.resource

import dev.architectury.fluid.FluidStack
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.minecraft.world.item.ItemStack

val ResourceStack<ItemResource>.itemStack: ItemStack get() = resource.toStack(amount.toInt())
val ResourceStack<FluidResource>.fluidStack: FluidStack get() = resource.toFluidStack(amount)
val ItemStack.resourceStack: ResourceStack<ItemResource> get() = ResourceStack(ItemResource.of(item, componentsPatch), count.toLong())
val FluidStack.resourceStack: ResourceStack<FluidResource> get() = ResourceStack(FluidResource.of(fluid, patch), amount)

fun FluidResource.toFluidStack(amount: Long): FluidStack = FluidStack.create(type, amount, dataPatch)

fun FluidResource.test(stack: FluidStack): Boolean
{
	return isOf(stack.fluid) && componentsMatch(stack.patch)
}

/**
 * This stack as a **kind-agnostic** [ResourceStack] - what a
 * [net.kernelpanicsoft.boilerplate.crafting.Pattern] cell is typed as, since a pattern may name a
 * fluid as readily as an item.
 *
 * A separate accessor rather than a use of [resourceStack] because [ResourceStack] is invariant in
 * its resource type: a `ResourceStack<ItemResource>` is not a `ResourceStack<ResourceComponent>`,
 * however obviously an item is a resource component, so the widening has to be built rather than
 * inferred.
 */
val ItemStack.resourceCell: ResourceStack<ResourceComponent>
	get() = ResourceStack(ItemResource.of(item, componentsPatch), count.toLong())
