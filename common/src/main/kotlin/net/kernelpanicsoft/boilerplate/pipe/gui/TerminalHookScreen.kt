package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.ResourceStack
import kotlinx.coroutines.delay
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.containers.TabContainerPanel
import net.kernelpanicsoft.archie.gui.layer.LocalLayerManager
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Box
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.archie.gui.theme.Theme
import net.kernelpanicsoft.boilerplate.network.RequestCraftJobTreePacket
import net.kernelpanicsoft.boilerplate.network.RequestCraftableListPacket
import net.kernelpanicsoft.boilerplate.network.RequestTerminalSearchResultsPacket
import net.kernelpanicsoft.boilerplate.network.RequestWarehouseDefragPacket
import net.kernelpanicsoft.boilerplate.network.SItemResource
import net.kernelpanicsoft.boilerplate.network.SResourceStack
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.util.itemStack
import net.kernelpanicsoft.boilerplate.util.resourceStack
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import kotlin.time.Duration.Companion.milliseconds

/**
 * A single "Store" tab over everything [menu] can currently reach (see [AbstractTerminalHookMenu]), plus a
 * "Tree" tab for the front-of-queue crafting job - see `docs/design/m3-warehouse-storage.md`/
 * `docs/design/m4-crafting-automation.md`. Built on Archie's `TabContainerPanel` (a fixed-width
 * `TabPanel` that wraps each tab's own content in its own `ContainerPanel`, tabs poking out of the
 * top the same way vanilla's own Create World screen looks) rather than the lower-level
 * `TabContainer` DSL, which has no width of its own and left the whole screen mismeasured/
 * left-pinned when tried first.
 *
 * There's no dedicated tab for browsing what's autocraftable - [StoreResultsGrid]'s own sidebar
 * [StoreViewModeButton] (Stock/Craft/All, AE2-terminal-style) filters the *same* grid instead,
 * with an autocraftable-but-empty entry showing "Craft" as its count and a click opening the
 * autocraft dialog directly, or a middle-click on an in-stock-and-craftable entry opening it
 * alongside the normal withdraw click - see [StoreResultsGrid]'s own KDoc. A real,
 * [Slots]-backed row of nine `"output"` slots sits underneath either way - a withdrawal or
 * finished crafting job delivers straight into this terminal's own
 * [net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState.output], not an external chest
 * wired to some other face.
 *
 * Hovering a result cell or a sidebar icon tracks [hoveredStack]/[sidebarTooltip], read back by
 * the overridden [renderTooltip] - the same hook `ComposeContainerScreen.render` already calls
 * vanilla's own `AbstractContainerScreen.renderTooltip` through, genuinely *after* the whole
 * Compose tree finishes rendering rather than nested inside it, which is the only reliable way to
 * guarantee a tooltip paints above everything else (a hovered [TerminalSlot]'s own
 * `SlotHighlight` included).
 */
class TerminalHookScreen(menu: TerminalHookMenu, playerInventory: Inventory, title: Component) :
	AbstractTerminalHookScreen<TerminalHookMenu>(menu, playerInventory, title)
{
	override val mainTabId: String get() = "terminal"
	override val mainTabLabel: Component get() = Component.literal("Terminal")
}
