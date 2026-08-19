package net.kernelpanicsoft.tubularstorage.registry

import dev.architectury.registry.menu.MenuRegistry
import kotlinx.serialization.ExperimentalSerializationApi
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.serialization.SerializationManager
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity
import net.kernelpanicsoft.tubularstorage.crafting.gui.AssemblyTableMenu
import net.kernelpanicsoft.tubularstorage.crafting.gui.AssemblyTableScreen
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.FilterCardMenu
import net.kernelpanicsoft.tubularstorage.pipe.gui.FilterCardScreen
import net.kernelpanicsoft.tubularstorage.pipe.gui.InterfaceHookMenu
import net.kernelpanicsoft.tubularstorage.pipe.gui.InterfaceHookScreen
import net.kernelpanicsoft.tubularstorage.pipe.gui.PatternProviderHookMenu
import net.kernelpanicsoft.tubularstorage.pipe.gui.PatternProviderHookScreen
import net.kernelpanicsoft.tubularstorage.pipe.gui.RequesterHookMenu
import net.kernelpanicsoft.tubularstorage.pipe.gui.RequesterHookScreen
import net.kernelpanicsoft.tubularstorage.pipe.gui.SortingHookMenu
import net.kernelpanicsoft.tubularstorage.pipe.gui.SortingHookScreen
import net.kernelpanicsoft.tubularstorage.pipe.gui.TerminalHookMenu
import net.kernelpanicsoft.tubularstorage.pipe.gui.TerminalHookScreen
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardTarget
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.world.inventory.MenuType

/** Registers Tubular Storage's [MenuType]s and their client-side screen factories. */
@OptIn(ExperimentalSerializationApi::class)
object GuiRegistry : ADeferredRegistryHolder<MenuType<*>>(TubularStorage.MOD, Registries.MENU) {
	val SortingHook: MenuType<SortingHookMenu> by register("sorting_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as HookBlockEntity
			val direction = buf.readEnum(Direction::class.java)
			SortingHookMenu(id, inventory, tile, direction)
		}
	}

	val RequesterHook: MenuType<RequesterHookMenu> by register("requester_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as HookBlockEntity
			val direction = buf.readEnum(Direction::class.java)
			RequesterHookMenu(id, inventory, tile, direction)
		}
	}

	val TerminalHook: MenuType<TerminalHookMenu> by register("terminal_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as HookBlockEntity
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
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as HookBlockEntity
			val direction = buf.readEnum(Direction::class.java)
			InterfaceHookMenu(id, inventory, tile, direction)
		}
	}

	val PatternProviderHook: MenuType<PatternProviderHookMenu> by register("pattern_provider_hook") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as HookBlockEntity
			val direction = buf.readEnum(Direction::class.java)
			PatternProviderHookMenu(id, inventory, tile, direction)
		}
	}

	val AssemblyTable: MenuType<AssemblyTableMenu> by register("assembly_table") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as AssemblyTableBlockEntity
			AssemblyTableMenu(id, inventory, tile)
		}
	}

	override fun initClient() {
		MenuRegistry.registerScreenFactory(SortingHook, ::SortingHookScreen)
		MenuRegistry.registerScreenFactory(RequesterHook, ::RequesterHookScreen)
		MenuRegistry.registerScreenFactory(TerminalHook, ::TerminalHookScreen)
		MenuRegistry.registerScreenFactory(FilterCard, ::FilterCardScreen)
		MenuRegistry.registerScreenFactory(InterfaceHook, ::InterfaceHookScreen)
		MenuRegistry.registerScreenFactory(PatternProviderHook, ::PatternProviderHookScreen)
		MenuRegistry.registerScreenFactory(AssemblyTable, ::AssemblyTableScreen)
	}
}
