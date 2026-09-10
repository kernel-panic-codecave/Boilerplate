package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class DistributedMultiTankScreen(menu: DistributedMultiTankMenu, playerInventory: Inventory, title: Component) :
	AbstractRackScreen<DistributedMultiTankBlockEntity, DistributedMultiTankMenu>(menu, playerInventory, title)
