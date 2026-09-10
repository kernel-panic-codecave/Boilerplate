package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.world.entity.player.Inventory

class DistributedMultiTankMenu(id: Int, inventory: Inventory, tile: DistributedMultiTankBlockEntity) :
	AbstractRackMenu<DistributedMultiTankBlockEntity, DistributedMultiTankMenu>(GuiRegistry.DistributedMultiTank, id, inventory, tile)
