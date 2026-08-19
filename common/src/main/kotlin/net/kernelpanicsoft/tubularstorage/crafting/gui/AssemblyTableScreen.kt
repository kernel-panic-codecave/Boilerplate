package net.kernelpanicsoft.tubularstorage.crafting.gui

import androidx.compose.runtime.Composable
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.composables.input.Button
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.theme.Theme
import net.kernelpanicsoft.tubularstorage.network.EncodeAssemblyPatternPacket
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * A vanilla-crafting-table-shaped 3x3 grid plus one output slot ([AssemblyTableMenu]'s shared
 * grid/output storage), an "Encode" button snapshotting the current contents into a new pattern
 * ([AssemblyTableMenu.encode], via [EncodeAssemblyPatternPacket]), and a running count of already-
 * encoded patterns - deliberately minimal, since exact pattern-editing UX is still open
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
				Column(verticalArrangement = Arrangement.spacedBy(6)) {
					Row(horizontalArrangement = Arrangement.spacedBy(18)) {
						Slots("grid", 3, 3)
						Slots("output", 1, 1)
					}
					Row(horizontalArrangement = Arrangement.spacedBy(6)) {
						Button(onClick = { TubularStorageNetworkChannel.toServer(EncodeAssemblyPatternPacket) }) {
							Text(Component.literal("Encode"), dropShadow = false)
						}
						Text(Component.literal("Patterns: ${menu.patterns.size}"), dropShadow = false)
					}
				}
			}
		}
	}
}
