package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.level.block.entity.BlockEntity

abstract class AbstractRackMenu<T, SELF : AbstractRackMenu<T, SELF>>(type: MenuType<SELF>, id: Int, inventory: Inventory, tile: T) : ComposeBlockContainerMenu<T, SELF>(type, id, inventory, tile) where T : BlockEntity, T : RackBlockEntity
{
	override fun registerSlotHandlers()
	{
		handler("filter", tile.filter) { it.item is FilterCardItem }
	}

	val pos: BlockPos get() = tile.blockPos
	var routing: RoutingModule get() = tile.routing
		set(value)
		{
			tile.routing = value
		}

	var priority: Int get() = tile.priority
		set(value)
		{
			tile.priority = value
		}
}