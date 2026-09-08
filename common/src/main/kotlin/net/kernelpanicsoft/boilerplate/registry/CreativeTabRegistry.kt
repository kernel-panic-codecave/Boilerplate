package net.kernelpanicsoft.boilerplate.registry

import net.kernelpanicsoft.archie.registries.CreativeTabRegistryHelper
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab

object CreativeTabRegistry : CreativeTabRegistryHelper<CreativeModeTab>(Boilerplate.MOD_ID)
{
	val Boilerplate: CreativeModeTab by create("boilerplate") {
		title(Component.translatable("boilerplate.tab"))
		icon { ItemRegistry.GlassPipe.defaultInstance }
		displayItems { parameters, output ->
			for ((_, item) in ItemRegistry) {
				item.listen {
					output.accept(it)
				}
			}
		}
	}
}