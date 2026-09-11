package net.kernelpanicsoft.boilerplate.creative

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.boilerplate.client.BoilerplateTheme
import net.kernelpanicsoft.boilerplate.pipe.gui.ResourceGhostSlot
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * The creative provider's screen: one ghost slot naming what it hands out, endlessly.
 *
 * A ghost slot rather than a real one, and the same one every other configuration surface uses
 * ([ResourceGhostSlot]) - so it takes any registered kind, and a bucket of water in hand sets *the
 * water* rather than the bucket, which is the only way a fluid is settable by clicking at all.
 * Clicking empty-handed clears it, which turns the block off.
 */
class CreativeProviderScreen(private val menu: CreativeProviderMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<CreativeProviderMenu>(menu, playerInventory, title) {

	init {
		start { content() }
	}

	@Composable
	fun content() {
		// The screen's own copy, so a click shows immediately rather than after the server echoes
		// it back; the synced value overwrites it on the next change from anywhere else.
		var provided by remember { mutableStateOf(menu.provided) }

		BoilerplateTheme {
			ContainerPanel(contentWidth = CONTENT_WIDTH) {
				Column(verticalArrangement = Arrangement.spacedBy(4), horizontalAlignment = Alignment.CenterHorizontally) {
					Text(Component.literal("Provides, endlessly"), dropShadow = false, color = LocalTheme.current.darkTextColor)
					ResourceGhostSlot(
						resource = provided,
						carried = { menu.carried },
						onPlace = { resource -> provided = resource; menu.requestProvide(resource) },
						onClear = { provided = ItemResource.BLANK; menu.requestProvide(ItemResource.BLANK) },
					)
				}
			}
		}
	}

	companion object {
		private const val CONTENT_WIDTH = 18 * 9
	}
}
