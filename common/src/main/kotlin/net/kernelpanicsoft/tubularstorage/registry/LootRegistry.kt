package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.block.MultipartContentsLootFunction
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType

/** Registers Tubular Storage's custom [LootItemFunctionType]s - see [MultipartContentsLootFunction]. */
object LootRegistry : ADeferredRegistryHolder<LootItemFunctionType<*>>(TubularStorage.MOD, Registries.LOOT_FUNCTION_TYPE) {
	val MultipartContents: LootItemFunctionType<*> by register("multipart_contents") {
		LootItemFunctionType(MultipartContentsLootFunction.CODEC)
	}
}
