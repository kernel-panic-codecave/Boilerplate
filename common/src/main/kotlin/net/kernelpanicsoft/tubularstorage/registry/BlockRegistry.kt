package net.kernelpanicsoft.tubularstorage.registry

import dev.architectury.registry.client.rendering.RenderTypeRegistry
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.util.blockProperties
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.block.GlassPipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.block.HookBlock
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/** Registers Tubular Storage's blocks. */
object BlockRegistry : ADeferredRegistryHolder<Block>(TubularStorage.MOD, Registries.BLOCK) {
	val Pipe: PipeBlock by register("pipe") {
		PipeBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val Hook: HookBlock by register("hook") {
		HookBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val GlassPipe: GlassPipeBlock by register("glass_pipe") {
		GlassPipeBlock(blockProperties(Blocks.GLASS) { })
	}

	override fun initClient() {
		RenderTypeRegistry.register(RenderType.translucent(), GlassPipe)
	}
}
