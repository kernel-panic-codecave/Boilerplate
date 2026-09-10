package net.kernelpanicsoft.boilerplate.registry

import dev.architectury.registry.client.rendering.RenderTypeRegistry
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.util.blockProperties
import net.kernelpanicsoft.archie.util.withMinecraftClient
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.block.*
import net.kernelpanicsoft.boilerplate.creative.CreativeProviderBlock
import net.kernelpanicsoft.boilerplate.power.block.CreativePressureSourceBlock
import net.kernelpanicsoft.boilerplate.power.block.PressurePipeBlock
import net.kernelpanicsoft.boilerplate.warehouse.block.GantryRailBlock
import net.kernelpanicsoft.boilerplate.warehouse.block.WarehouseControllerBlock
import net.kernelpanicsoft.boilerplate.warehouse.rack.*
import net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlock
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
		GeneralRackBlock(blockProperties(Blocks.BARREL) {
			noOcclusion()
			requiresCorrectToolForDrops()
		})
	}

	val BulkRack: BulkRackBlock by register("bulk_rack") {
		BulkRackBlock(blockProperties(Blocks.IRON_BLOCK) {
			noOcclusion()
			requiresCorrectToolForDrops()
		})
	}

	val UnstackableRack: UnstackableRackBlock by register("unstackable_rack") {
		UnstackableRackBlock(blockProperties(Blocks.IRON_BLOCK) {
			noOcclusion()
			requiresCorrectToolForDrops()
		})
	}

	val DistributedMultiTank: DistributedMultiTankBlock by register("distributed_multi_tank") {
		DistributedMultiTankBlock(blockProperties(Blocks.IRON_BLOCK) {
			noOcclusion()
			requiresCorrectToolForDrops()
		})
	}

	val DistributedMultiBuffer: DistributedMultiBufferBlock by register("distributed_multi_buffer") {
		DistributedMultiBufferBlock(blockProperties(Blocks.IRON_BLOCK) {
			noOcclusion()
			requiresCorrectToolForDrops()
		})
	}

	val Omnibuffer: OmnibufferBlock by register("omnibuffer") {
		OmnibufferBlock(blockProperties(Blocks.IRON_BLOCK) {
			noOcclusion()
			requiresCorrectToolForDrops()
		})
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

	val FluidTank: FluidTankBlock by register("fluid_tank") {
		FluidTankBlock(blockProperties(Blocks.IRON_BLOCK) { requiresCorrectToolForDrops() })
	}

	val CreativeProvider: CreativeProviderBlock by register("creative_provider") {
		CreativeProviderBlock(blockProperties(Blocks.IRON_BLOCK) { requiresCorrectToolForDrops() })
	}

	val CreativePressureSource: CreativePressureSourceBlock by register("creative_pressure_source") {
		CreativePressureSourceBlock(blockProperties(Blocks.IRON_BLOCK) { requiresCorrectToolForDrops() })
	}

	override fun init()
	{
		super.init()
		listen {
			// [withMinecraftClient], not [onClient]: a data run is the client distribution with no
			// game behind it, so an onClient block runs there too - and NeoForge refuses a render
			// layer set outside client loading ("Render layers can only be set during client
			// loading"). A run that draws nothing needs none.
			withMinecraftClient { RenderTypeRegistry.register(RenderType.cutout(), GantryRail) }
		}
	}
}
