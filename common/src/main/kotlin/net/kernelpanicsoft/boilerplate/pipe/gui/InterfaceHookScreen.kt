package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.theme.Theme
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/** A plain 3x3 [Slots] grid over [InterfaceHookMenu]'s `stock` group - the whole GUI, chest-simple, matching [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType]'s "normal interaction is like the ME interface" role. */
class InterfaceHookScreen(private val menu: InterfaceHookMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<InterfaceHookMenu>(menu, playerInventory, title) {

	init {
		start { content() }
	}

	@Composable
	fun content() {
		Theme {
			ContainerPanel {
				Slots("stock", 3, 3)
			}
		}
	}
}
