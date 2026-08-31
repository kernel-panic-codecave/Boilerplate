package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.world.entity.player.Inventory

class GeneralRackMenu(id: Int, inventory: Inventory, tile: GeneralRackBlockEntity) : AbstractRackMenu<GeneralRackBlockEntity, GeneralRackMenu>(GuiRegistry.GeneralRack, id, inventory, tile)