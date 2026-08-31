package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.*
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.Scrollable
import net.kernelpanicsoft.archie.gui.composables.input.Button
import net.kernelpanicsoft.archie.gui.composables.input.textfield.BasicTextField
import net.kernelpanicsoft.archie.gui.interaction.MutableInteractionSource
import net.kernelpanicsoft.archie.gui.interaction.collectIsHoveredAsState
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.height
import net.kernelpanicsoft.archie.gui.modifiers.input.hoverable
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.boilerplate.network.SItemResource
import net.kernelpanicsoft.boilerplate.network.SResourceStack
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

private const val COLUMNS = 9
private const val VISIBLE_ROWS = 3

/**
 * Which entries [StoreResultsGrid] shows - the AE2-style "sidebar cycle button" this mod's own
 * terminal family uses instead of a dedicated tab for browsing what's autocraftable (see
 * [StoreViewModeButton]). [next] is the cycle order a click on that button steps through.
 */
enum class StoreViewMode(val label: String, val tooltip: String) {
	AVAILABLE("Stock", "Showing: in stock"),
	CRAFTABLE("Craft", "Showing: craftable"),
	BOTH("All", "Showing: in stock + craftable");

	fun next(): StoreViewMode = entries[(ordinal + 1) % entries.size]
}

/** One combined row of [StoreResultsGrid] - [amount] is `0` for a craftable entry with nothing currently in stock. */
data class StoreEntry(val resource: SItemResource, val amount: Long, val craftable: Boolean)

/** Merges [results] (real stock) and [craftable] (distinct craftable resources) into [StoreEntry]s, filtered down to [mode]'s own subset. */
fun combineStoreEntries(results: List<SResourceStack<SItemResource>>, craftable: List<SItemResource>, mode: StoreViewMode): List<StoreEntry> {
	val craftableSet = craftable.toSet()
	val stockByResource = LinkedHashMap<ItemResource, Long>()
	for (stack in results) stockByResource[stack.resource] = stack.amount

	val order = LinkedHashSet<ItemResource>()
	when (mode) {
		StoreViewMode.AVAILABLE -> order += stockByResource.keys
		StoreViewMode.CRAFTABLE -> order += craftableSet
		StoreViewMode.BOTH -> {
			order += stockByResource.keys
			order += craftableSet
		}
	}

	return order.map { resource -> StoreEntry(resource, stockByResource[resource] ?: 0L, resource in craftableSet) }
}

/**
 * The terminal family's shared search/results grid - [mode]-filtered [StoreEntry]s over
 * [results]/[craftable], [COLUMNS] wide and always at least [VISIBLE_ROWS] tall, scrolling for
 * more. A cell's interaction depends on what it actually holds:
 * - In stock ([StoreEntry.amount] `> 0`): the real count shows, a normal click opens
 *   [onRequestWithdraw]'s quantity dialog.
 * - Craftable but out of stock: the count badge reads "Craft" instead of a number, and a normal
 *   click opens [onRequestCraft]'s dialog directly - there's nothing to withdraw.
 * - In stock *and* craftable: a normal click still withdraws; middle-click additionally opens
 *   [onRequestCraft]'s dialog (see [ClickHandler]/[TerminalSlot]'s own `onMiddleClick`).
 *
 * A non-empty [carried] cursor overrides all of the above - any click deposits it via
 * [onDepositCarried] instead, matching every other terminal grid in this mod.
 *
 * A single, non-menu-specific composable (unlike the sibling menu classes it's used from) since
 * nothing about search/filter/click-routing actually depends on which concrete terminal menu is
 * open - only the callbacks do.
 *
 * @param enabled When `false` (the owning terminal hook itself has no reachable pressure - see
 *   [AbstractTerminalHookMenu.hasPressure]), every cell draws [TextureStates.DISABLED][net.kernelpanicsoft.archie.gui.composables.theme.TextureStates.DISABLED]'s own
 *   greyed-out slot texture and ignores clicks entirely, rather than merely showing an already-empty
 *   [results]/[craftable].
 */
@Composable
fun StoreResultsGrid(
	results: List<SResourceStack<SItemResource>>,
	craftable: List<SItemResource>,
	mode: StoreViewMode,
	contentWidth: Int,
	carried: () -> ItemStack,
	onDepositCarried: () -> Unit,
	onRequestWithdraw: (ResourceStack<ItemResource>) -> Unit,
	onRequestCraft: (ItemResource) -> Unit,
	clickHandler: ClickHandler,
	onHoveredStackChanged: (SResourceStack<SItemResource>?) -> Unit,
	enabled: Boolean = true,
) {
	var query by remember { mutableStateOf("") }
	var hoveredStack by remember { mutableStateOf<SResourceStack<SItemResource>?>(null) }
	LaunchedEffect(hoveredStack) { onHoveredStackChanged(hoveredStack) }

	val entries = remember(results, craftable, mode) { combineStoreEntries(results, craftable, mode) }
	val filtered = entries.filter { query.isBlank() || it.resource.cachedStack.hoverName.string.contains(query, ignoreCase = true) }
	val rows = maxOf(VISIBLE_ROWS, (filtered.size + COLUMNS - 1) / COLUMNS)

	Column(verticalArrangement = Arrangement.spacedBy(6)) {
		BasicTextField(
			value = query,
			onValueChange = { query = it },
			modifier = Modifier.width(contentWidth),
		)

		Scrollable(modifier = Modifier.width(contentWidth).height(18 * VISIBLE_ROWS)) {
			Column {
				for (row in 0 until rows) {
					Row {
						for (column in 0 until COLUMNS) {
							val entry = filtered.getOrNull(row * COLUMNS + column)
							val stack = entry?.let { ResourceStack(it.resource, if (it.amount > 0) it.amount else 1L) }
							val craftableInStock = entry != null && entry.amount > 0 && entry.craftable
							TerminalSlot(
								stack = stack,
								countText = entry?.takeIf { it.amount <= 0 }?.let { "Craft" },
								onClick = {
									if (carried() != ItemStack.EMPTY) {
										onDepositCarried()
									} else if (entry != null) {
										if (entry.amount > 0) onRequestWithdraw(ResourceStack(entry.resource, entry.amount))
										else if (entry.craftable) onRequestCraft(entry.resource)
									}
								},
								onHovered = { hovered -> hoveredStack = if (hovered) stack else if (hoveredStack === stack) null else hoveredStack },
								clickHandler = clickHandler,
								handleClick = if (craftableInStock) ({ onRequestCraft(entry!!.resource) }) else null,
								enabled = enabled,
							)
						}
					}
				}
			}
		}
	}
}

/**
 * A themed [Button] that additionally reports [tooltip] to [onHoveredTooltip] while hovered - the
 * sidebar's own refresh/defrag/[StoreViewModeButton] icons are a single unicode glyph each, not
 * self-explanatory on their own. [Button] itself doesn't expose hover state to its own content,
 * so this layers [Modifier.hoverable] on top independently rather than forking [Button].
 */
@Composable
fun SidebarButton(label: String, tooltip: String, onHoveredTooltip: (String?) -> Unit, onClick: () -> Unit) {
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	LaunchedEffect(isHovered) { onHoveredTooltip(if (isHovered) tooltip else null) }

	Button(onClick = { onClick() }, modifier = Modifier.hoverable(interactionSource)) {
		Text(Component.literal(label), dropShadow = false)
	}
}

/** Cycles [mode] through [StoreViewMode.entries] on click - see [StoreResultsGrid]'s own KDoc. */
@Composable
fun StoreViewModeButton(mode: StoreViewMode, onHoveredTooltip: (String?) -> Unit, onCycle: (StoreViewMode) -> Unit) {
	SidebarButton(mode.label, mode.tooltip, onHoveredTooltip) { onCycle(mode.next()) }
}
