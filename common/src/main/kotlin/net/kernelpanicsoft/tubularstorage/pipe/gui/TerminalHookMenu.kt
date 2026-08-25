package net.kernelpanicsoft.tubularstorage.pipe.gui

import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.MenuType

class TerminalHookMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction
) : AbstractTerminalHookMenu<TerminalHookMenu>(GuiRegistry.TerminalHook, id, inventory, tile, direction)
{
}