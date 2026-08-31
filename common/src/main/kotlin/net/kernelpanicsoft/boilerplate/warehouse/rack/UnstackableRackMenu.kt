package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.world.entity.player.Inventory

class UnstackableRackMenu(id: Int, inventory: Inventory, tile: UnstackableRackBlockEntity) : AbstractRackMenu<UnstackableRackBlockEntity, UnstackableRackMenu>(GuiRegistry.UnstackableRack, id, inventory, tile)