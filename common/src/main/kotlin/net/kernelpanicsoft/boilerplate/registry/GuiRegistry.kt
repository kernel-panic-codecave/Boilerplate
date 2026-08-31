package net.kernelpanicsoft.boilerplate.registry

import dev.architectury.registry.menu.MenuRegistry
import kotlinx.serialization.ExperimentalSerializationApi
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.serialization.SerializationManager
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.crafting.gui.CraftingBufferMenu
import net.kernelpanicsoft.boilerplate.crafting.gui.CraftingBufferScreen
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.gui.*
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardTarget
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerMenu
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerScreen
import net.kernelpanicsoft.boilerplate.warehouse.rack.*
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.world.inventory.MenuType

/** Registers Boilerplate's [MenuType]s and their client-side screen factories. */
@OptIn(ExperimentalSerializationApi::class)
object GuiRegistry : ADeferredRegistryHolder<MenuType<*>>(Boilerplate.MOD, Registries.MENU) {
	val SortingHook: MenuType<SortingHookMenu> by register("sorting_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as MultipartBlockEntity
			val direction = buf.readEnum(Direction::class.java)
			SortingHookMenu(id, inventory, tile, direction)
		}
	}

	val RequesterHook: MenuType<RequesterHookMenu> by register("requester_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as MultipartBlockEntity
			val direction = buf.readEnum(Direction::class.java)
			RequesterHookMenu(id, inventory, tile, direction)
		}
	}

	val TerminalHook: MenuType<TerminalHookMenu> by register("terminal_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as MultipartBlockEntity
			val direction = buf.readEnum(Direction::class.java)
			TerminalHookMenu(id, inventory, tile, direction)
		}
	}

	val FilterCard: MenuType<FilterCardMenu> by register("filter_card") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val target = SerializationManager.cbor.decodeFromByteArray(FilterCardTarget.serializer(), buf.readByteArray())
			FilterCardMenu(id, inventory, target)
		}
	}

	val InterfaceHook: MenuType<InterfaceHookMenu> by register("interface_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as MultipartBlockEntity
			val direction = buf.readEnum(Direction::class.java)
			InterfaceHookMenu(id, inventory, tile, direction)
		}
	}

	val PatternProviderHook: MenuType<PatternProviderHookMenu> by register("pattern_provider_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as MultipartBlockEntity
			val direction = buf.readEnum(Direction::class.java)
			PatternProviderHookMenu(id, inventory, tile, direction)
		}
	}

	val CraftingTerminalHook: MenuType<CraftingTerminalHookMenu> by register("crafting_terminal_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as MultipartBlockEntity
			val direction = buf.readEnum(Direction::class.java)
			CraftingTerminalHookMenu(id, inventory, tile, direction)
		}
	}

	val PatternTerminalHook: MenuType<PatternTerminalHookMenu> by register("pattern_terminal_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as MultipartBlockEntity
			val direction = buf.readEnum(Direction::class.java)
			PatternTerminalHookMenu(id, inventory, tile, direction)
		}
	}

	val CraftingBuffer: MenuType<CraftingBufferMenu> by register("crafting_buffer") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as MultipartBlockEntity
			CraftingBufferMenu(id, inventory, tile)
		}
	}

	val BulkRack: MenuType<BulkRackMenu> by register("bulk_rack") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as BulkRackBlockEntity
			BulkRackMenu(id, inventory, tile)
		}
	}

	val GeneralRack: MenuType<GeneralRackMenu> by register("general_rack") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as GeneralRackBlockEntity
			GeneralRackMenu(id, inventory, tile)
		}
	}

	val UnstackableRack: MenuType<UnstackableRackMenu> by register("unstackable_rack") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as UnstackableRackBlockEntity
			UnstackableRackMenu(id, inventory, tile)
		}
	}

	val WarehouseController: MenuType<WarehouseControllerMenu> by register("warehouse_controller") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as WarehouseControllerBlockEntity
			WarehouseControllerMenu(id, inventory, tile)
		}
	}

	override fun initClient() {
		MenuRegistry.registerScreenFactory(SortingHook, ::SortingHookScreen)
		MenuRegistry.registerScreenFactory(RequesterHook, ::RequesterHookScreen)
		MenuRegistry.registerScreenFactory(TerminalHook, ::TerminalHookScreen)
		MenuRegistry.registerScreenFactory(FilterCard, ::FilterCardScreen)
		MenuRegistry.registerScreenFactory(InterfaceHook, ::InterfaceHookScreen)
		MenuRegistry.registerScreenFactory(PatternProviderHook, ::PatternProviderHookScreen)
		MenuRegistry.registerScreenFactory(CraftingTerminalHook, ::CraftingTerminalHookScreen)
		MenuRegistry.registerScreenFactory(PatternTerminalHook, ::PatternTerminalHookScreen)
		MenuRegistry.registerScreenFactory(CraftingBuffer, ::CraftingBufferScreen)
		MenuRegistry.registerScreenFactory(BulkRack, ::BulkRackScreen)
		MenuRegistry.registerScreenFactory(GeneralRack, ::GeneralRackScreen)
		MenuRegistry.registerScreenFactory(UnstackableRack, ::UnstackableRackScreen)
		MenuRegistry.registerScreenFactory(WarehouseController, ::WarehouseControllerScreen)
	}
}
