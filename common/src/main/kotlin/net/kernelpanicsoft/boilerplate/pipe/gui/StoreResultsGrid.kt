package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.*
import dev.architectury.platform.Platform
import kotlinx.serialization.Serializable
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.Scrollable
import net.kernelpanicsoft.archie.gui.composables.input.Button
import net.kernelpanicsoft.archie.gui.composables.input.textfield.BasicTextField
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.appearance.tooltip
import net.kernelpanicsoft.archie.gui.modifiers.height
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.resource.ResourceIdentity
import net.kernelpanicsoft.boilerplate.resource.SResourceComponent
import net.kernelpanicsoft.boilerplate.resource.SResourceStack
import net.kernelpanicsoft.boilerplate.resource.displayName
import net.kernelpanicsoft.boilerplate.util.FuzzySearch
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

private const val COLUMNS = 9
private const val VISIBLE_ROWS = 3

/**
 * Which entries [StoreResultsGrid] shows - the AE2-style "sidebar cycle button" this mod's own
 * terminal family uses instead of a dedicated tab for browsing what's autocraftable (see
 * [StoreViewModeButton]). [next] is the cycle order a click on that button steps through.
 *
 * @property label A single glyph, matching the rest of the sidebar column: the button is an
 *   18px-wide icon among other 18px icons, and a word would be the only thing in that column wide
 *   enough to set its width. One family of squares rather than three unrelated pictograms, so the
 *   three states read as one three-way control - filled for what the network physically holds,
 *   hollow for what it could only make, and both nested for the union. [tooltip] carries the
 *   meaning; the glyph only has to say which of the three is current.
 * @property tooltip Shown on hover - see [SidebarButton].
 */
@Serializable
enum class StoreViewMode(val label: String, val tooltip: String) {
	AVAILABLE("■", "Showing: in stock"),
	CRAFTABLE("□", "Showing: craftable"),
	BOTH("▣", "Showing: in stock + craftable");

	fun next(): StoreViewMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Which key [StoreResultsGrid] orders its rows by - the second of the terminal's sidebar cycle
 * buttons (see [StoreSortModeButton]), paired with a [StoreSortDirection] toggle beside it.
 *
 * @property label A single glyph, for the same reason [StoreViewMode.label] is one. Semantic rather
 *   than a shape family here: a sort key is three unrelated choices, not one ordinal progression, so
 *   the glyphs say *what is being compared* instead of implying an order between themselves.
 * @property tooltip Shown on hover - see [SidebarButton].
 */
@Serializable
enum class StoreSortMode(val label: String, val tooltip: String) {
	NAME("A", "Sorting: name"),
	AMOUNT("#", "Sorting: amount"),
	MOD("⚙", "Sorting: mod");

	fun next(): StoreSortMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Which way [StoreSortMode]'s comparison runs - the whole comparison, tie-break included, so
 * descending by amount also lists equal amounts Z to A rather than leaving a half-reversed order.
 *
 * @property label A single glyph, for the same reason [StoreViewMode.label] is one.
 * @property tooltip Shown on hover - see [SidebarButton].
 */
@Serializable
enum class StoreSortDirection(val label: String, val tooltip: String) {
	ASCENDING("▲", "Order: ascending"),
	DESCENDING("▼", "Order: descending");

	fun next(): StoreSortDirection = entries[(ordinal + 1) % entries.size]
}

/**
 * The terminal's own view and sort selection, held in the client config so it survives the screen
 * closing - a player who sorts by amount expects to find it sorted by amount the next time, and a
 * per-composition `remember` forgets on every close.
 *
 * Written through here rather than direct to [BoilerplateConfig] so the file write is not something
 * a caller can forget: a bare assignment to a config field only updates the loaded value, and the
 * setting would then last until the game closed and no longer. Saving on each click rather than at
 * screen close is what makes the value survive a crash, and it costs one small file write per
 * deliberate button press.
 */
object TerminalPreferences {
	/** Which rows a terminal lists - see [StoreViewMode]. */
	var viewMode: StoreViewMode
		get() = BoilerplateConfig.Visuals.Interface.terminalViewMode
		set(value) = persist { BoilerplateConfig.Visuals.Interface.terminalViewMode = value }

	/** Which key a terminal orders its rows by - see [StoreSortMode]. */
	var sortMode: StoreSortMode
		get() = BoilerplateConfig.Visuals.Interface.terminalSortMode
		set(value) = persist { BoilerplateConfig.Visuals.Interface.terminalSortMode = value }

	/** Which way [sortMode] runs - see [StoreSortDirection]. */
	var sortDirection: StoreSortDirection
		get() = BoilerplateConfig.Visuals.Interface.terminalSortDirection
		set(value) = persist { BoilerplateConfig.Visuals.Interface.terminalSortDirection = value }

	private inline fun persist(write: () -> Unit) {
		write()
		BoilerplateConfig.Visuals.save()
	}
}

/** One combined row of [StoreResultsGrid] - [amount] is `0` for a craftable entry with nothing currently in stock. */
data class StoreEntry(val resource: SResourceComponent, val amount: Long, val craftable: Boolean)

/**
 * Merges [results] (real stock) and [craftable] (distinct craftable resources) into [StoreEntry]s,
 * filtered down to [mode]'s own subset.
 *
 * Every map and set here is keyed by [ResourceIdentity] rather than by the resource: a terminal
 * lists whatever the network holds, which is no longer only items, and `FluidResource` has no value
 * equality of its own - keying on the resource would make each mention of one fluid a separate row
 * and the craftable lookup would never match it.
 */
fun combineStoreEntries(results: List<SResourceStack<*>>, craftable: List<SResourceComponent>, mode: StoreViewMode): List<StoreEntry> {
	val craftableSet = craftable.mapTo(LinkedHashSet()) { ResourceIdentity.of(it) }
	val stockByResource = LinkedHashMap<ResourceIdentity, Long>()
	for (stack in results) stockByResource[ResourceIdentity.of(stack.resource as ResourceComponent)] = stack.amount

	val order = LinkedHashSet<ResourceIdentity>()
	when (mode) {
		StoreViewMode.AVAILABLE -> order += stockByResource.keys
		StoreViewMode.CRAFTABLE -> order += craftableSet
		StoreViewMode.BOTH -> {
			order += stockByResource.keys
			order += craftableSet
		}
	}

	return order.map { key -> StoreEntry(key.resource, stockByResource[key] ?: 0L, key in craftableSet) }
}

/**
 * [entries] ordered by [mode], reversed whole for [StoreSortDirection.DESCENDING].
 *
 * Every mode falls back to the name, so the ordering is total: two rows tying on the primary key
 * hold a fixed position relative to one another instead of drifting as the network's contents
 * change underneath them.
 *
 * [StoreSortMode.AMOUNT] compares **authored** amounts - the number the cell itself draws. Across
 * kinds that is the only comparison available at all (a bucket and a stack of cobblestone share no
 * unit), and comparing platform amounts instead would additionally make one fluid row outrank every
 * item row on Fabric and not on NeoForge, purely from the droplet count.
 */
fun sortStoreEntries(entries: List<StoreEntry>, mode: StoreSortMode, direction: StoreSortDirection): List<StoreEntry> {
	if (entries.size < 2) return entries
	// Keys are built once per row rather than read from inside the comparator: displayName()
	// allocates a Component (and, for an item, builds a stack to ask it), and the mod lookup goes
	// through the platform's own mod list - far too much to pay the O(n log n) times a
	// comparison-time selector would be called on a network holding thousands of rows.
	val names = Array(entries.size) { entries[it].resource.displayName().string }
	val byName = compareBy<Int, String>(String.CASE_INSENSITIVE_ORDER) { names[it] }
	val comparator = when (mode) {
		StoreSortMode.NAME -> byName
		StoreSortMode.AMOUNT -> {
			val amounts = LongArray(entries.size) { authoredStockOf(entries[it]) }
			compareBy<Int> { amounts[it] }.then(byName)
		}
		StoreSortMode.MOD -> {
			val mods = Array(entries.size) { modNameOf(entries[it].resource) }
			compareBy<Int, String>(String.CASE_INSENSITIVE_ORDER) { mods[it] }.then(byName)
		}
	}
	val ordered = if (direction == StoreSortDirection.ASCENDING) comparator else comparator.reversed()
	return entries.indices.sortedWith(ordered).map { entries[it] }
}

/**
 * [entry]'s stock in its own kind's authored unit, or its raw amount for a resource whose kind is
 * no longer registered (an addon removed while a world was saved).
 */
private fun authoredStockOf(entry: StoreEntry): Long =
	ResourceKindRegistry.forResource(entry.resource)?.toAuthored(entry.amount) ?: entry.amount

/**
 * The display name of the mod [resource] came from - "Mekanism", not "mekanism" - falling back to
 * the bare namespace for a mod that reports none, and to the empty string for a resource with no
 * registry id at all, which sorts it to the top ascending.
 *
 * The same name the resource's own tooltip shows (see
 * [net.kernelpanicsoft.boilerplate.client.resourceTooltip]), so grouping by mod groups by the label
 * the player already reads there.
 */
private fun modNameOf(resource: SResourceComponent): String {
	val namespace = ResourceKindRegistry.forResource(resource)?.registryId(resource)?.namespace ?: return ""
	return Platform.getOptionalMod(namespace).map { it.name }.orElse(namespace)!!
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
 *   [onRequestCraft]'s dialog (see [TerminalSlot]'s own `handleClick`).
 *
 * A non-empty [carried] cursor overrides all of the above - any click deposits it via
 * [onDepositCarried] instead, matching every other terminal grid in this mod. **Right**-clicking
 * asks for the container's *contents* rather than the container - a bucket of water emptied into
 * the network, the bundle's own gesture for the same idea - because a filled bucket is a reasonable
 * thing to want stored either way and only the player knows which they meant.
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
	results: List<SResourceStack<*>>,
	craftable: List<SResourceComponent>,
	mode: StoreViewMode,
	sortMode: StoreSortMode,
	sortDirection: StoreSortDirection,
	contentWidth: Int,
	carried: () -> ItemStack,
	onDepositCarried: (drainContainer: Boolean) -> Unit,
	onRequestWithdraw: (ResourceStack<ResourceComponent>) -> Unit,
	onRequestCraft: (ResourceComponent) -> Unit,
	onHoveredStackChanged: (SResourceStack<*>?) -> Unit,
	enabled: Boolean = true,
) {
	var query by remember { mutableStateOf("") }
	var hoveredIndex by remember { mutableStateOf<Int?>(null) }

	val entries = remember(results, craftable, mode) { combineStoreEntries(results, craftable, mode) }
	// Matched on the kind's own display name rather than an item stack's hover name, so a fluid row
	// is searchable by the same text the row itself shows, and matched approximately so a typo
	// reads as a typo instead of as an empty network - see [FuzzySearch].
	//
	// A filter, not a ranking: the rows that survive are then ordered by whatever the sidebar's own
	// sort says, because that is a choice the player made and a relevance score would quietly
	// override it. Sorted after the search rather than before it so only the rows actually on screen
	// are ever compared, and cached against every input that can change the order - a network
	// holding thousands of rows would otherwise re-search and re-sort on every frame that
	// recomposes for any other reason.
	val filtered = remember(entries, query, sortMode, sortDirection) {
		sortStoreEntries(
			entries.filter { FuzzySearch.matches(it.resource.displayName().string, query) },
			sortMode,
			sortDirection,
		)
	}
	val rows = maxOf(VISIBLE_ROWS, (filtered.size + COLUMNS - 1) / COLUMNS)

	val hoveredEntry = hoveredIndex?.let { filtered.getOrNull(it) }
	val hoveredStack = hoveredEntry?.let { ResourceStack(it.resource, if (it.amount > 0) it.amount else 1L) }
	LaunchedEffect(hoveredEntry?.resource, hoveredEntry?.amount) { onHoveredStackChanged(hoveredStack) }

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
							val index = row * COLUMNS + column
							val entry = filtered.getOrNull(index)
							val stack = entry?.let { ResourceStack(it.resource, if (it.amount > 0) it.amount else 1L) }
							val craftableInStock = entry != null && entry.amount > 0 && entry.craftable
							TerminalSlot(
								stack = stack,
								// "Craft" for an out-of-stock craftable, otherwise whatever this
								// kind needs written in the corner - see amountLabelFor.
								onClick = {
									if (carried() != ItemStack.EMPTY) {
										onDepositCarried(false)
									} else if (entry != null) {
										if (entry.amount > 0) onRequestWithdraw(ResourceStack(entry.resource, entry.amount))
										else if (entry.craftable) onRequestCraft(entry.resource)
									}
								},
								onHovered = { hovered -> hoveredIndex = if (hovered) index else if (hoveredIndex == index) null else hoveredIndex },
								countText = if (entry != null && entry.amount <= 0) "Craft"
								else amountLabelFor(entry?.resource, entry?.amount ?: 0L),
								handleRightClick = { onDepositCarried(true) },
								handleMiddleClick = if (craftableInStock) ({ onRequestCraft(entry.resource) }) else null,
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
 * A themed [Button] showing [tooltip] on hover - the sidebar's own refresh/defrag/
 * [StoreViewModeButton] icons are a single unicode glyph each, not self-explanatory on their own.
 */
@Composable
fun SidebarButton(label: String, tooltip: String, onClick: () -> Unit) {
	Button(onClick = { onClick() }, modifier = Modifier.tooltip(Component.literal(tooltip))) {
		Text(Component.literal(label), dropShadow = false)
	}
}

/** Cycles [mode] through [StoreViewMode.entries] on click - see [StoreResultsGrid]'s own KDoc. */
@Composable
fun StoreViewModeButton(mode: StoreViewMode, onCycle: (StoreViewMode) -> Unit) {
	SidebarButton(mode.label, mode.tooltip) { onCycle(mode.next()) }
}

/** Cycles [mode] through [StoreSortMode.entries] on click - see [sortStoreEntries]. */
@Composable
fun StoreSortModeButton(mode: StoreSortMode, onCycle: (StoreSortMode) -> Unit) {
	SidebarButton(mode.label, mode.tooltip) { onCycle(mode.next()) }
}

/**
 * Flips [direction] on click - its own button rather than three more [StoreSortMode] constants,
 * so changing the key keeps the direction and vice versa.
 */
@Composable
fun StoreSortDirectionButton(direction: StoreSortDirection, onCycle: (StoreSortDirection) -> Unit) {
	SidebarButton(direction.label, direction.tooltip) { onCycle(direction.next()) }
}
