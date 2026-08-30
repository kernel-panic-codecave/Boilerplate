package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class BulkRackScreen(menu: BulkRackMenu, playerInventory: Inventory, title: Component) : AbstractRackScreen<BulkRackBlockEntity, BulkRackMenu>(menu, playerInventory, title)