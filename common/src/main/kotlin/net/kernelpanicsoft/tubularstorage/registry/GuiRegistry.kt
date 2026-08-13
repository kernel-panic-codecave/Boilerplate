package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.minecraft.core.registries.Registries
import net.minecraft.world.inventory.MenuType

/**
 * Registers Tubular Storage's [MenuType]s and their client-side screen factories.
 *
 * Empty until the first block with a screen (M2's sorting pipe) is implemented.
 */
object GuiRegistry : ADeferredRegistryHolder<MenuType<*>>(TubularStorage.MOD, Registries.MENU)
