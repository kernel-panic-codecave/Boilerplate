package net.kernelpanicsoft.boilerplate.pipe.gui

import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.MenuType

class TerminalHookMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction
) : AbstractTerminalHookMenu<TerminalHookMenu>(GuiRegistry.TerminalHook, id, inventory, tile, direction)
{
}