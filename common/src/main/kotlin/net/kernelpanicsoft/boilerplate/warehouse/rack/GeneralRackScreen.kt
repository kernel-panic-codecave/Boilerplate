package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class GeneralRackScreen(menu: GeneralRackMenu, playerInventory: Inventory, title: Component) : AbstractRackScreen<GeneralRackBlockEntity, GeneralRackMenu>(menu, playerInventory, title)