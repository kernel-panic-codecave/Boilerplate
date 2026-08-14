package net.kernelpanicsoft.tubularstorage.registry

import dev.architectury.registry.menu.MenuRegistry
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.SortingPipeMenu
import net.kernelpanicsoft.tubularstorage.pipe.gui.SortingPipeScreen
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.world.inventory.MenuType

/** Registers Tubular Storage's [MenuType]s and their client-side screen factories. */
object GuiRegistry : ADeferredRegistryHolder<MenuType<*>>(TubularStorage.MOD, Registries.MENU) {
	val SortingPipe: MenuType<SortingPipeMenu> by register("sorting_pipe") {
		MenuRegistry.ofExtended { id, inventory, buf ->
			val tile = inventory.player.level().getBlockEntity(buf.readBlockPos()) as HookBlockEntity
			val direction = buf.readEnum(Direction::class.java)
			SortingPipeMenu(id, inventory, tile, direction)
		}
	}

	override fun initClient() {
		MenuRegistry.registerScreenFactory(SortingPipe, ::SortingPipeScreen)
	}
}
