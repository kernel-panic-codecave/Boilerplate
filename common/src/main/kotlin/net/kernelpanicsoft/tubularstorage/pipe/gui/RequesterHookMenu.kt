package net.kernelpanicsoft.tubularstorage.pipe.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Inventory

class RequesterHookMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<HookBlockEntity, RequesterHookMenu>(GuiRegistry.RequesterHook, id, inventory, tile)
{
	override fun registerSlotHandlers()
	{
	}
}