package net.kernelpanicsoft.boilerplate.pipe.gui

import net.kernelpanicsoft.archie.gui.Slots
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

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
 * Result cells and sidebar icons declare their own tooltips
 * ([net.kernelpanicsoft.archie.gui.modifiers.appearance.tooltip]); `ComposeContainerScreen.render`
 * draws them after the whole Compose tree has finished, which is what guarantees a tooltip paints
 * above everything else (a hovered [TerminalSlot]'s own `SlotHighlight` included). Hovering a
 * result cell separately tracks [hoveredStack], which a recipe viewer reads as its focused stack.
 */
class TerminalHookScreen(menu: TerminalHookMenu, playerInventory: Inventory, title: Component) :
	AbstractTerminalHookScreen<TerminalHookMenu>(menu, playerInventory, title)
{
	override val mainTabId: String get() = "terminal"
	override val mainTabLabel: Component get() = Component.literal("Terminal")
}
