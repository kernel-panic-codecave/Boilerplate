package net.kernelpanicsoft.boilerplate.warehouse.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity

class WarehouseControllerMenu(id: Int, inventory: Inventory, tile: WarehouseControllerBlockEntity) :
	ComposeBlockContainerMenu<WarehouseControllerBlockEntity, WarehouseControllerMenu>(GuiRegistry.WarehouseController, id, inventory, tile) {

	override fun registerSlotHandlers() {
		handler("filter", tile.filter) { it.item is FilterCardItem }
	}

	val pos: BlockPos get() = tile.blockPos
	var routing: RoutingModule get() = tile.routing
		set(value)
		{
			tile.routing = value
		}
}