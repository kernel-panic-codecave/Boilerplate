package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.boilerplate.gui.BoilerplateTheme
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FILTER_CARD_CONTENT_WIDTH
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardEditor
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * The standalone host for the card editor - what right-clicking a card **in hand** opens, where
 * there is no screen to layer over.
 *
 * Everything it draws is [FilterCardEditorContent], the same composable the editor layer shows
 * anywhere else, so the two can never drift. The menu behind it ([FilterCardMenu]) exists only to be
 * a screen: the editing itself goes through [FilterCardEditor] against a
 * [FilterCardTarget.PlayerSlot], not through the menu's own fields.
 */
class FilterCardScreen(private val menu: FilterCardMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<FilterCardMenu>(menu, playerInventory, title) {

	init {
		start { content() }
	}

	@Composable
	fun content() {
		BoilerplateTheme {
			// ContainerPanel, not the Panel-plus-PlayerSlots a layer assembles by hand: nothing is
			// hosting this one, so it is free to take the standard container layout - title label,
			// contents, player inventory - wholesale.
			ContainerPanel(contentWidth = FILTER_CARD_CONTENT_WIDTH) {
				FilterCardEditorContent(
					editor = FilterCardEditor(menu.target) { menu.carried },
				)
			}
		}
	}
}
