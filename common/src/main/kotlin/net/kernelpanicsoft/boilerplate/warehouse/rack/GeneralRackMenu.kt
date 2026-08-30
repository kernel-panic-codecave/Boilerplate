package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.world.entity.player.Inventory

class BulkRackMenu(id: Int, inventory: Inventory, tile: BulkRackBlockEntity) : AbstractRackMenu<BulkRackBlockEntity, BulkRackMenu>(GuiRegistry.BulkRack, id, inventory, tile)