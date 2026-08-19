package net.kernelpanicsoft.tubularstorage.util

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.minecraft.world.item.ItemStack

val ResourceStack<ItemResource>.itemStack: ItemStack get() = resource.toStack(amount.toInt())
val ItemStack.resourceStack: ResourceStack<ItemResource> get() = ResourceStack(ItemResource.of(item, componentsPatch), count.toLong())