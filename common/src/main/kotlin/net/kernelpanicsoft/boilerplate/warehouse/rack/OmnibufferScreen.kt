package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class OmnibufferScreen(menu: OmnibufferMenu, playerInventory: Inventory, title: Component) :
	AbstractRackScreen<OmnibufferBlockEntity, OmnibufferMenu>(menu, playerInventory, title)
