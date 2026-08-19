package net.kernelpanicsoft.tubularstorage.pipe.gui

import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

class RequesterHookScreen(private val menu: RequesterHookMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<RequesterHookMenu>(menu, playerInventory, title)
{
	private val contentWidth = 18 * 9

	init
	{
		start {  }
	}

}