package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.world.entity.player.Inventory

class DistributedMultiBufferMenu(id: Int, inventory: Inventory, tile: DistributedMultiBufferBlockEntity) :
	AbstractRackMenu<DistributedMultiBufferBlockEntity, DistributedMultiBufferMenu>(GuiRegistry.DistributedMultiBuffer, id, inventory, tile)
