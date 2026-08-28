package net.kernelpanicsoft.boilerplate.pipe.gui

import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Inventory

class RequesterHookMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<MultipartBlockEntity, RequesterHookMenu>(GuiRegistry.RequesterHook, id, inventory, tile)
{
	override fun registerSlotHandlers()
	{
	}
}