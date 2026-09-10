package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class DistributedMultiBufferScreen(menu: DistributedMultiBufferMenu, playerInventory: Inventory, title: Component) :
	AbstractRackScreen<DistributedMultiBufferBlockEntity, DistributedMultiBufferMenu>(menu, playerInventory, title)
