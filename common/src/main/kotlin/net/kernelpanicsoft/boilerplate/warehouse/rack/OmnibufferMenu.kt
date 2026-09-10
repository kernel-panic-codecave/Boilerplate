package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.world.entity.player.Inventory

class OmnibufferMenu(id: Int, inventory: Inventory, tile: OmnibufferBlockEntity) :
	AbstractRackMenu<OmnibufferBlockEntity, OmnibufferMenu>(GuiRegistry.Omnibuffer, id, inventory, tile)
