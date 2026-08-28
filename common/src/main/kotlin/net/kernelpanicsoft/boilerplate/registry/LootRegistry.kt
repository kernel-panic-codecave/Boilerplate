package net.kernelpanicsoft.boilerplate.registry

import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.block.MultipartContentsLootFunction
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType

/** Registers Boilerplate's custom [LootItemFunctionType]s - see [MultipartContentsLootFunction]. */
object LootRegistry : ADeferredRegistryHolder<LootItemFunctionType<*>>(Boilerplate.MOD, Registries.LOOT_FUNCTION_TYPE) {
	val MultipartContents: LootItemFunctionType<*> by register("multipart_contents") {
		LootItemFunctionType(MultipartContentsLootFunction.CODEC)
	}
}
