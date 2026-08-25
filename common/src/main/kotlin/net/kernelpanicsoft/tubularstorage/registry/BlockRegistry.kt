package net.kernelpanicsoft.tubularstorage.registry

import dev.architectury.registry.client.rendering.RenderTypeRegistry
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.util.blockProperties
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.block.*
import net.kernelpanicsoft.tubularstorage.warehouse.GantryRailBlock
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlock
import net.kernelpanicsoft.tubularstorage.warehouse.rack.BulkRackBlock
import net.kernelpanicsoft.tubularstorage.warehouse.rack.GeneralRackBlock
import net.kernelpanicsoft.tubularstorage.warehouse.rack.UnstackableRackBlock
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/** Registers Tubular Storage's blocks. */
object BlockRegistry : ADeferredRegistryHolder<Block>(TubularStorage.MOD, Registries.BLOCK) {
	val Pipe: PipeBlock by register("pipe") {
		PipeBlock(blockProperties(Blocks.IRON_BLOCK) {
			noOcclusion()
			requiresCorrectToolForDrops()
		})
	}

	val GlassPipe: GlassPipeBlock by register("glass_pipe") {
		GlassPipeBlock(blockProperties(Blocks.GLASS) {
			noOcclusion()
			requiresCorrectToolForDrops()
		})
	}

	val Multipart: MultipartBlock by register("multipart") {
		MultipartBlock(blockProperties(Blocks.IRON_BLOCK) {
			noOcclusion()
			requiresCorrectToolForDrops()
		})
	}

	val ExtractionHook by register("extraction_part") {
		BistateHookModelBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val FilterHook by register("filter_part") {
		BistateHookModelBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val ProviderHook by register("provider_part") {
		BistateHookModelBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val SyncHook by register("sync_part") {
		BistateHookModelBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val RequesterHook by register("requester_part") {
		BistateHookModelBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val TerminalHook by register("terminal_part") {
		BistateHookModelBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val InterfaceHook by register("interface_part") {
		BistateHookModelBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val CraftingTerminalHook by register("crafting_terminal_part") {
		BistateHookModelBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val PatternProviderHook by register("pattern_provider_part") {
		BistateHookModelBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val PatternTerminalHook by register("pattern_terminal_part") {
		BistateHookModelBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val CraftingBufferPart: ConnectingEncasementModelBlock by register("crafting_buffer_part") {
		ConnectingEncasementModelBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val WarehouseController: WarehouseControllerBlock by register("warehouse_controller") {
		WarehouseControllerBlock(blockProperties(Blocks.IRON_BLOCK) { requiresCorrectToolForDrops() })
	}

	val GantryRail: GantryRailBlock by register("gantry_rail") {
		GantryRailBlock(blockProperties(Blocks.IRON_BLOCK) {
			noOcclusion()
			requiresCorrectToolForDrops()
		})
	}

	val GeneralRack: GeneralRackBlock by register("general_rack") {
		GeneralRackBlock(blockProperties(Blocks.BARREL) { requiresCorrectToolForDrops() })
	}

	val BulkRack: BulkRackBlock by register("bulk_rack") {
		BulkRackBlock(blockProperties(Blocks.IRON_BLOCK) { requiresCorrectToolForDrops() })
	}

	val UnstackableRack: UnstackableRackBlock by register("unstackable_rack") {
		UnstackableRackBlock(blockProperties(Blocks.IRON_BLOCK) { requiresCorrectToolForDrops() })
	}


	override fun initClient() {
		RenderTypeRegistry.register(RenderType.cutout(), GantryRail)
	}
}
