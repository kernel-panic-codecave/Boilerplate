package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.util.itemProperties
import net.kernelpanicsoft.archie.util.tab
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.WarehouseTerminalHookType
import net.kernelpanicsoft.tubularstorage.pipe.item.HookItem
import net.kernelpanicsoft.tubularstorage.pipe.item.PipeItem
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseWandItem
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item

/** Registers Tubular Storage's items, including the [BlockItem]s for [BlockRegistry.Pipe]/[BlockRegistry.GlassPipe]. */
object ItemRegistry : ADeferredRegistryHolder<Item>(TubularStorage.MOD, Registries.ITEM) {
	val Pipe by register("pipe") {
		PipeItem(BlockRegistry.Pipe, itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) })
	}

	val GlassPipe by register("glass_pipe") {
		PipeItem(BlockRegistry.GlassPipe, itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) })
	}

	val ExtractionHook by register("extraction_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = ExtractionHookType.ID)
	}

	val SortingHook by register("sorting_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = SortingHookType.ID)
	}

	val ProviderHook by register("provider_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = ProviderHookType.ID)
	}

	val RequesterHook by register("requester_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = RequesterHookType.ID)
	}

	val WarehouseTerminalHook by register("warehouse_terminal_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = WarehouseTerminalHookType.ID)
	}

	val WarehouseController by register("warehouse_controller") {
		BlockItem(BlockRegistry.WarehouseController, itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) })
	}

	val WarehouseWand by register("warehouse_wand") {
		WarehouseWandItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES); stacksTo(1) })
	}

	/** No creative tab - [BlockRegistry.GantryRail] is auto-placed by binding a warehouse, not hand-placed. */
	val GantryRail by register("gantry_rail") { BlockItem(BlockRegistry.GantryRail, itemProperties {}) }

	/**
	 * Not player-obtainable (no creative tab) - exists purely as a registered [Item] so its model
	 * bakes through the normal `ItemModelShaper` path, for
	 * [net.kernelpanicsoft.tubularstorage.warehouse.client.WarehouseControllerBlockEntityRenderer]
	 * to look up and draw as the gantry crane head's own dynamic render.
	 */
	val GantryHead by register("gantry_head") { Item(itemProperties {}) }
}
