package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.util.itemProperties
import net.kernelpanicsoft.archie.util.tab
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.crafting.PatternItem
import net.kernelpanicsoft.tubularstorage.pipe.hook.CraftingTerminalHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.InterfaceHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternTerminalHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.FilterHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.SyncHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.ColorConditionType
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.CombinedConditionType
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.ItemConditionType
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.ModConditionType
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.RegexConditionType
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.TagConditionType
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

	val FilterHook by register("filter_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = FilterHookType.ID)
	}

	val ProviderHook by register("provider_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = ProviderHookType.ID)
	}

	val SyncHook by register("sync_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = SyncHookType.ID)
	}

	val RequesterHook by register("requester_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = RequesterHookType.ID)
	}

	val TerminalHook by register("terminal_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = TerminalHookType.ID)
	}

	val InterfaceHook by register("interface_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = InterfaceHookType.ID)
	}

	val CraftingTerminalHook by register("crafting_terminal_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = CraftingTerminalHookType.ID)
	}

	val PatternProviderHook by register("pattern_provider_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = PatternProviderHookType.ID)
	}

	val PatternTerminalHook by register("pattern_terminal_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = PatternTerminalHookType.ID)
	}

	val WarehouseController by register("warehouse_controller") {
		BlockItem(BlockRegistry.WarehouseController, itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) })
	}

	val WarehouseWand by register("warehouse_wand") {
		WarehouseWandItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES); stacksTo(1) })
	}

	val GeneralRack by register("general_rack") {
		BlockItem(BlockRegistry.GeneralRack, itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) })
	}

	val BulkRack by register("bulk_rack") {
		BlockItem(BlockRegistry.BulkRack, itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) })
	}

	val UnstackableRack by register("unstackable_rack") {
		BlockItem(BlockRegistry.UnstackableRack, itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) })
	}

	val AssemblyTable by register("assembly_table") {
		BlockItem(BlockRegistry.AssemblyTable, itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) })
	}

	/** See [PatternItem]'s own KDoc - blank until encoded, one item type for both states. */
	val Pattern by register("pattern") {
		PatternItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) })
	}

	/**
	 * One item per registered [net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterConditionType],
	 * mirroring [ExtractionHook]/[ProviderHook]/etc. above - see [FilterCardItem]'s own KDoc for why
	 * a card's kind is fixed by which item it is, not switchable after the fact.
	 * [stacksTo(1)][net.minecraft.world.item.Item.Properties.stacksTo] - a stack shares one
	 * [net.minecraft.world.item.ItemStack]'s worth of component data, and this one's own config is
	 * exactly what makes each card distinct, unlike a plain stackable item.
	 */
	val ItemFilterCard by register("item_filter_card") {
		FilterCardItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES); stacksTo(1) }, conditionTypeId = ItemConditionType.ID)
	}

	val ModFilterCard by register("mod_filter_card") {
		FilterCardItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES); stacksTo(1) }, conditionTypeId = ModConditionType.ID)
	}

	val TagFilterCard by register("tag_filter_card") {
		FilterCardItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES); stacksTo(1) }, conditionTypeId = TagConditionType.ID)
	}

	val ColorFilterCard by register("color_filter_card") {
		FilterCardItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES); stacksTo(1) }, conditionTypeId = ColorConditionType.ID)
	}

	val RegexFilterCard by register("regex_filter_card") {
		FilterCardItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES); stacksTo(1) }, conditionTypeId = RegexConditionType.ID)
	}

	val CombinedFilterCard by register("combined_filter_card") {
		FilterCardItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES); stacksTo(1) }, conditionTypeId = CombinedConditionType.ID)
	}

	/** No creative tab - [BlockRegistry.GantryRail] is auto-placed by binding a warehouse, not hand-placed. */
	val GantryRail by register("gantry_rail") { BlockItem(BlockRegistry.GantryRail, itemProperties {}) }

	/**
	 * Not player-obtainable (no creative tab) - exists purely as a registered [Item] so its model
	 * bakes through the normal `ItemModelShaper` path, for
	 * [net.kernelpanicsoft.tubularstorage.warehouse.client.WarehouseControllerBlockEntityRenderer]
	 * to look up and draw as the gantry crane head's own dynamic render.
	 */
//	val GantryHead by register("gantry_head") { Item(itemProperties {}) }
}
