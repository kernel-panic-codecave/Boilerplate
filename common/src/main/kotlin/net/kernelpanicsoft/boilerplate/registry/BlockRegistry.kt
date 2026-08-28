package net.kernelpanicsoft.boilerplate.registry

import dev.architectury.registry.client.rendering.RenderTypeRegistry
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.util.blockProperties
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.block.*
import net.kernelpanicsoft.boilerplate.power.block.CreativePressureSourceBlock
import net.kernelpanicsoft.boilerplate.power.block.PressurePipeBlock
import net.kernelpanicsoft.boilerplate.warehouse.GantryRailBlock
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlock
import net.kernelpanicsoft.boilerplate.warehouse.rack.BulkRackBlock
import net.kernelpanicsoft.boilerplate.warehouse.rack.GeneralRackBlock
import net.kernelpanicsoft.boilerplate.warehouse.rack.UnstackableRackBlock
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/** Registers Boilerplate's blocks. */
object BlockRegistry : ADeferredRegistryHolder<Block>(Boilerplate.MOD, Registries.BLOCK) {
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

	val AdapterHook by register("adapter_part") {
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

	val PressurePipe: PressurePipeBlock by register("pressure_pipe") {
		PressurePipeBlock(blockProperties(Blocks.COPPER_BLOCK) {
			noOcclusion()
			requiresCorrectToolForDrops()
		})
	}

	val CompressorPart: ConnectingEncasementModelBlock by register("compressor_part") {
		ConnectingEncasementModelBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val PressureTankPart: ConnectingEncasementModelBlock by register("pressure_tank_part") {
		ConnectingEncasementModelBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val CreativePressureSource: CreativePressureSourceBlock by register("creative_pressure_source") {
		CreativePressureSourceBlock(blockProperties(Blocks.IRON_BLOCK) { requiresCorrectToolForDrops() })
	}

	override fun initClient() {
		RenderTypeRegistry.register(RenderType.cutout(), GantryRail)
	}
}
