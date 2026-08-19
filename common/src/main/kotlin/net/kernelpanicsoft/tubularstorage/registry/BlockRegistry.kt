package net.kernelpanicsoft.tubularstorage.registry

import dev.architectury.registry.client.rendering.RenderTypeRegistry
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.util.blockProperties
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.block.GlassPipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.block.HookBlock
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
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
		})
	}

	val Hook: HookBlock by register("hook") {
		HookBlock(blockProperties(Blocks.IRON_BLOCK) {
			noOcclusion()
		})
	}

	val GlassPipe: GlassPipeBlock by register("glass_pipe") {
		GlassPipeBlock(blockProperties(Blocks.GLASS) {
			noOcclusion()
		})
	}

	val WarehouseController: WarehouseControllerBlock by register("warehouse_controller") {
		WarehouseControllerBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val GantryRail: GantryRailBlock by register("gantry_rail") {
		GantryRailBlock(blockProperties(Blocks.IRON_BLOCK) {
			noOcclusion()
		})
	}

	val GeneralRack: GeneralRackBlock by register("general_rack") {
		GeneralRackBlock(blockProperties(Blocks.BARREL) { })
	}

	val BulkRack: BulkRackBlock by register("bulk_rack") {
		BulkRackBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	val UnstackableRack: UnstackableRackBlock by register("unstackable_rack") {
		UnstackableRackBlock(blockProperties(Blocks.IRON_BLOCK) { })
	}

	override fun initClient() {
		RenderTypeRegistry.register(RenderType.cutout(), GantryRail)
	}
}
