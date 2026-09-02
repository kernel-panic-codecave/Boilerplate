package net.kernelpanicsoft.boilerplate.util

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.minecraft.world.item.ItemStack

val ResourceStack<ItemResource>.itemStack: ItemStack get() = resource.toStack(amount.toInt())
val ItemStack.resourceStack: ResourceStack<ItemResource> get() = ResourceStack(ItemResource.of(item, componentsPatch), count.toLong())

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
