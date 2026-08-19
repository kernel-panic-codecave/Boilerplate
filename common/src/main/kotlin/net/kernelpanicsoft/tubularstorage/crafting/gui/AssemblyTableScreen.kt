package net.kernelpanicsoft.tubularstorage.crafting.gui

import androidx.compose.runtime.Composable
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.theme.Theme
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * A vanilla-crafting-table-shaped 3x3 grid plus one output slot ([AssemblyTableMenu]'s real,
 * synced slots) - no pattern authoring here, that moved to a dedicated Pattern Terminal. Watching
 * the grid empty into the output slot over [net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity.PROCESSING_TIME_TICKS]
 * ticks is its own visible progress indicator; a dedicated progress bar is left for later polish
 * (`docs/design/m4-crafting-automation.md`).
 */
class AssemblyTableScreen(private val menu: AssemblyTableMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<AssemblyTableMenu>(menu, playerInventory, title) {

	init {
		start { content() }
	}

	@Composable
	fun content() {
		Theme {
			ContainerPanel(contentWidth = 18 * 9) {
				Row(horizontalArrangement = Arrangement.spacedBy(18)) {
					Slots("grid", 3, 3)
					Slots("output", 1, 1)
				}
			}
		}
	}
}
