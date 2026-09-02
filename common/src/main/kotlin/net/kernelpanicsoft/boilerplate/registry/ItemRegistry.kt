package net.kernelpanicsoft.boilerplate.registry

import dev.architectury.event.EventResult
import dev.architectury.event.events.client.ClientScreenInputEvent
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.util.itemProperties
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.archie.util.tab
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.Boilerplate.MOD_ID
import net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType
import net.kernelpanicsoft.boilerplate.crafting.CraftingTankEncasementType
import net.kernelpanicsoft.boilerplate.crafting.Pattern.Companion.EMPTY
import net.kernelpanicsoft.boilerplate.crafting.PatternItem
import net.kernelpanicsoft.boilerplate.crafting.PatternItemData
import net.kernelpanicsoft.boilerplate.item.WrenchItem
import net.kernelpanicsoft.boilerplate.item.WrenchTier
import net.kernelpanicsoft.boilerplate.network.OpenFilterCardEditorPacket
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.pipe.hook.*
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.*
import net.kernelpanicsoft.boilerplate.pipe.item.EncasementItem
import net.kernelpanicsoft.boilerplate.pipe.item.HookItem
import net.kernelpanicsoft.boilerplate.pipe.item.PipeItem
import net.kernelpanicsoft.boilerplate.power.CompressorEncasementType
import net.kernelpanicsoft.boilerplate.power.PressureTankEncasementType
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseWandItem
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.item.ItemProperties
import net.minecraft.core.registries.Registries
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.*

/** Registers Boilerplate's items, including the [BlockItem]s for [BlockRegistry.Pipe]/[BlockRegistry.GlassPipe]. */
object ItemRegistry : ADeferredRegistryHolder<Item>(Boilerplate.MOD, Registries.ITEM) {
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

	val AdapterHook by register("adapter_hook") {
		HookItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, hookId = AdapterHookType.ID)
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

	val PressurePipe by register("pressure_pipe") {
		PipeItem(BlockRegistry.PressurePipe, itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) })
	}

	val PressureTankEncasement by register("pressure_tank_encasement") {
		EncasementItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, encasementId = PressureTankEncasementType.ID)
	}

	val CompressorEncasement by register("compressor_encasement") {
		EncasementItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, encasementId = CompressorEncasementType.ID)
	}

	/** Never crafted - reachable only via the creative inventory/`/give`, matching [BlockRegistry.CreativePressureSource]'s own never-craftable role. */
	val CreativePressureSource by register("creative_pressure_source") {
		BlockItem(BlockRegistry.CreativePressureSource, itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) })
	}

	/** Named `<encasement type path>_encasement`, mirroring how each [HookItem] above is named `<hook type path>_hook` - the datagen'd item model and [net.kernelpanicsoft.boilerplate.pipe.client.MultipartBlockEntityVisual]'s own model lookup both rely on that convention. */
	val CraftingBufferEncasement by register("crafting_buffer_encasement") {
		EncasementItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, encasementId = CraftingBufferEncasementType.ID)
	}

	val CraftingTankEncasement by register("crafting_tank_encasement") {
		EncasementItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) }, encasementId = CraftingTankEncasementType.ID)
	}

	/** See [PatternItem]'s own KDoc - blank until encoded, one item type for both states. */
	val Pattern by register("pattern") {
		PatternItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES) })
	}

	/**
	 * One item per registered [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterConditionType],
	 * mirroring [ExtractionHook]/[ProviderHook]/etc. above - see [FilterCardItem]'s own KDoc for why
	 * a card's kind is fixed by which item it is, not switchable after the fact.
	 * [stacksTo(1)][net.minecraft.world.item.Item.Properties.stacksTo] - a stack shares one
	 * [net.minecraft.world.item.ItemStack]'s worth of component data, and this one's own config is
	 * exactly what makes each card distinct, unlike a plain stackable item.
	 */
	val ItemFilterCard by register("item_filter_card") {
		FilterCardItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES); stacksTo(1) }, conditionTypeId = ItemConditionType.ID)
	}

	val FluidFilterCard by register("fluid_filter_card") {
		FilterCardItem(itemProperties { tab(CreativeModeTabs.TOOLS_AND_UTILITIES); stacksTo(1) }, conditionTypeId = FluidConditionType.ID)
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
	 * The harvesting tool for every player-facing Boilerplate block - see [WrenchItem]
	 * and the `boilerplate:mineable/wrench` block tag those blocks carry. The
	 * [net.minecraft.world.item.component.Tool] component here is what actually answers vanilla's
	 * correct-tool check: one rule matching that tag at high mining speed, dropping the block.
	 */
	val BrassWrench by register("brass_wrench") {
		WrenchItem(
			WrenchTier.Brass,
			itemProperties {
				tab(CreativeModeTabs.TOOLS_AND_UTILITIES)
				stacksTo(1)
				attributes(DiggerItem.createAttributes(WrenchTier.Brass, 1.5f, -3.0f))
			}
		)
	}

	val DiamondWrench by register("diamond_wrench") {
		WrenchItem(
			WrenchTier.Diamond,
			itemProperties {
				tab(CreativeModeTabs.TOOLS_AND_UTILITIES)
				stacksTo(1)
				attributes(DiggerItem.createAttributes(WrenchTier.Diamond, 1.5f, -3.0f))
			}
		)
	}

	override fun initClient()
	{
		ItemProperties.register(Pattern, MOD_ID % "encoded") { itemStack, clientLevel, livingEntity, i ->
			if (PatternItemData(itemStack).pattern != EMPTY) 1f
			else 0f
		}
		fun findPlayerInventoryIndex(player: Player, slot: Slot): Int? {
			val stack = slot.item
			if (stack.isEmpty) return null

			val inv = player.inventory

			// 1. Fast path: normal survival slots
			if (slot.container === inv) {
				val idx = slot.containerSlot
				if (idx in 0 until inv.containerSize && inv.getItem(idx) === stack) {
					return idx
				}
			}

			// 2. Creative (or any wrapper): search by identity
			for (i in 0 until inv.containerSize) {
				if (inv.getItem(i) === stack) return i
			}

			// 3. Fallback: equal stack in the same slot range (hotbar / main)
			for (i in 0 until inv.containerSize) {
				if (ItemStack.isSameItemSameComponents(inv.getItem(i), stack)) return i
			}

			return null
		}
		ClientScreenInputEvent.MOUSE_CLICKED_PRE.register { client, screen, mouseX, mouseY, button ->
			if (button != 1) return@register EventResult.pass()
			if (screen !is AbstractContainerScreen<*>) return@register EventResult.pass()

			val slot = screen.hoveredSlot ?: return@register EventResult.pass()
			val stack = slot.item
			if (stack.item !is FilterCardItem) return@register EventResult.pass()

			val player = client.player ?: return@register EventResult.pass()
			if (player.isSpectator) return@register EventResult.pass()

			// Resolve a real inventory index (works in survival + creative)
			val inventoryIndex = findPlayerInventoryIndex(player, slot)
				?: return@register EventResult.pass()

			val target = FilterCardTarget.PlayerSlot(inventoryIndex)
			BoilerplateNetworkChannel.toServer(OpenFilterCardEditorPacket(target))
			EventResult.interruptFalse()
		}
	}
}
