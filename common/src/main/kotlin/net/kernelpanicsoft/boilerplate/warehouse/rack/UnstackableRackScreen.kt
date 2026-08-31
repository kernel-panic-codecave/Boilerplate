package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class UnstackableRackScreen(menu: UnstackableRackMenu, playerInventory: Inventory, title: Component) : AbstractRackScreen<UnstackableRackBlockEntity, UnstackableRackMenu>(menu, playerInventory, title)