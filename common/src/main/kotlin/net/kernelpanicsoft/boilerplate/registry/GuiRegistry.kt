package net.kernelpanicsoft.boilerplate.registry

import dev.architectury.registry.menu.MenuRegistry
import kotlinx.serialization.ExperimentalSerializationApi
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.serialization.SerializationManager
import net.kernelpanicsoft.archie.util.onClient
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.crafting.gui.CraftingBufferMenu
import net.kernelpanicsoft.boilerplate.crafting.gui.CraftingBufferScreen
import net.kernelpanicsoft.boilerplate.creative.CreativeProviderBlockEntity
import net.kernelpanicsoft.boilerplate.creative.CreativeProviderMenu
import net.kernelpanicsoft.boilerplate.creative.CreativeProviderScreen
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.gui.*
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardTarget
import net.kernelpanicsoft.boilerplate.util.blockEntity
import net.kernelpanicsoft.boilerplate.util.level
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.gui.WarehouseControllerMenu
import net.kernelpanicsoft.boilerplate.warehouse.gui.WarehouseControllerScreen
import net.kernelpanicsoft.boilerplate.warehouse.rack.*
import net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankMenu
import net.kernelpanicsoft.boilerplate.pipe.gui.ExtractionHookMenu
import net.kernelpanicsoft.boilerplate.pipe.gui.ExtractionHookScreen
import net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankScreen
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.world.inventory.MenuType

/** Registers Boilerplate's [MenuType]s and their client-side screen factories. */
@OptIn(ExperimentalSerializationApi::class)
object GuiRegistry : ADeferredRegistryHolder<MenuType<*>>(Boilerplate.MOD, Registries.MENU) {
	val SortingHook: MenuType<SortingHookMenu> by register("sorting_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<MultipartBlockEntity>(buf.readBlockPos())!!
			val direction = buf.readEnum(Direction::class.java)
			SortingHookMenu(id, inventory, tile, direction)
		}
	}

	val ExtractionHook: MenuType<ExtractionHookMenu> by register("extraction_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<MultipartBlockEntity>(buf.readBlockPos())!!
			val direction = buf.readEnum(Direction::class.java)
			ExtractionHookMenu(id, inventory, tile, direction)
		}
	}

	val RequesterHook: MenuType<RequesterHookMenu> by register("requester_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<MultipartBlockEntity>(buf.readBlockPos())!!
			val direction = buf.readEnum(Direction::class.java)
			RequesterHookMenu(id, inventory, tile, direction)
		}
	}

	val TerminalHook: MenuType<TerminalHookMenu> by register("terminal_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<MultipartBlockEntity>(buf.readBlockPos())!!
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
			val tile = inventory.player.level.blockEntity<MultipartBlockEntity>(buf.readBlockPos())!!
			val direction = buf.readEnum(Direction::class.java)
			InterfaceHookMenu(id, inventory, tile, direction)
		}
	}

	val PatternProviderHook: MenuType<PatternProviderHookMenu> by register("pattern_provider_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<MultipartBlockEntity>(buf.readBlockPos())!!
			val direction = buf.readEnum(Direction::class.java)
			PatternProviderHookMenu(id, inventory, tile, direction)
		}
	}

	val CraftingTerminalHook: MenuType<CraftingTerminalHookMenu> by register("crafting_terminal_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<MultipartBlockEntity>(buf.readBlockPos())!!
			val direction = buf.readEnum(Direction::class.java)
			CraftingTerminalHookMenu(id, inventory, tile, direction)
		}
	}

	val PatternTerminalHook: MenuType<PatternTerminalHookMenu> by register("pattern_terminal_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<MultipartBlockEntity>(buf.readBlockPos())!!
			val direction = buf.readEnum(Direction::class.java)
			PatternTerminalHookMenu(id, inventory, tile, direction)
		}
	}

	val CraftingBuffer: MenuType<CraftingBufferMenu> by register("crafting_buffer") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<MultipartBlockEntity>(buf.readBlockPos())!!
			CraftingBufferMenu(id, inventory, tile)
		}
	}

	val BulkRack: MenuType<BulkRackMenu> by register("bulk_rack") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<BulkRackBlockEntity>(buf.readBlockPos())!!
			BulkRackMenu(id, inventory, tile)
		}
	}

	val GeneralRack: MenuType<GeneralRackMenu> by register("general_rack") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<GeneralRackBlockEntity>(buf.readBlockPos())!!
			GeneralRackMenu(id, inventory, tile)
		}
	}

	val UnstackableRack: MenuType<UnstackableRackMenu> by register("unstackable_rack") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<UnstackableRackBlockEntity>(buf.readBlockPos())!!
			UnstackableRackMenu(id, inventory, tile)
		}
	}

	val DistributedMultiTank: MenuType<DistributedMultiTankMenu> by register("distributed_multi_tank") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<DistributedMultiTankBlockEntity>(buf.readBlockPos())!!
			DistributedMultiTankMenu(id, inventory, tile)
		}
	}

	val DistributedMultiBuffer: MenuType<DistributedMultiBufferMenu> by register("distributed_multi_buffer") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<DistributedMultiBufferBlockEntity>(buf.readBlockPos())!!
			DistributedMultiBufferMenu(id, inventory, tile)
		}
	}

	val Omnibuffer: MenuType<OmnibufferMenu> by register("omnibuffer") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<OmnibufferBlockEntity>(buf.readBlockPos())!!
			OmnibufferMenu(id, inventory, tile)
		}
	}

	val CreativeProvider: MenuType<CreativeProviderMenu> by register("creative_provider") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<CreativeProviderBlockEntity>(buf.readBlockPos())!!
			CreativeProviderMenu(id, inventory, tile)
		}
	}

	val FluidTank: MenuType<FluidTankMenu> by register("fluid_tank") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<FluidTankBlockEntity>(buf.readBlockPos())!!
			FluidTankMenu(id, inventory, tile)
		}
	}

	val WarehouseController: MenuType<WarehouseControllerMenu> by register("warehouse_controller") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level.blockEntity<WarehouseControllerBlockEntity>(buf.readBlockPos())!!
			WarehouseControllerMenu(id, inventory, tile)
		}
	}

	override fun init() {
		super.init()
		listen {
			onClient {
				MenuRegistry.registerScreenFactory(SortingHook, ::SortingHookScreen)
				MenuRegistry.registerScreenFactory(ExtractionHook, ::ExtractionHookScreen)
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
				MenuRegistry.registerScreenFactory(FluidTank, ::FluidTankScreen)
				MenuRegistry.registerScreenFactory(CreativeProvider, ::CreativeProviderScreen)
				MenuRegistry.registerScreenFactory(DistributedMultiTank, ::DistributedMultiTankScreen)
				MenuRegistry.registerScreenFactory(DistributedMultiBuffer, ::DistributedMultiBufferScreen)
				MenuRegistry.registerScreenFactory(Omnibuffer, ::OmnibufferScreen)
				MenuRegistry.registerScreenFactory(WarehouseController, ::WarehouseControllerScreen)
			}
		}
	}
}
