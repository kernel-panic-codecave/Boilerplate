package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.util.itemProperties
import net.kernelpanicsoft.archie.util.tab
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item

/** Registers Tubular Storage's items, including the [BlockItem]s for [BlockRegistry]'s blocks. */
object ItemRegistry : ADeferredRegistryHolder<Item>(TubularStorage.MOD, Registries.ITEM) {
	val Pipe by register("pipe") {
		BlockItem(BlockRegistry.Pipe, itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) })
	}

	val ExtractorPipe by register("extractor_pipe") {
		BlockItem(BlockRegistry.ExtractorPipe, itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) })
	}
}
