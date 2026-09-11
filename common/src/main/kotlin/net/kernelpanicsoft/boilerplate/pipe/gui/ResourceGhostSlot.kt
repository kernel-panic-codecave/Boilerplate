package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.archie.gui.composables.input.Clickable
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.appearance.tooltip
import net.kernelpanicsoft.archie.gui.modifiers.input.MouseButton
import net.kernelpanicsoft.archie.gui.modifiers.input.onScroll
import net.kernelpanicsoft.archie.gui.nodes.UINode
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.resource.ResourceKind
import net.kernelpanicsoft.boilerplate.resource.displayName
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import kotlin.math.sign

/**
 * One cell of a ghost grid that accepts **either** an item or a fluid - what a `PROCESSING`
 * [net.kernelpanicsoft.boilerplate.crafting.Pattern]'s cells need, since such a pattern may name
 * either on either side.
 *
 * The gesture is the union of the two single-kind slots it replaces, and unambiguous because the
 * two cases can't collide: clicking with a **fluid-containing** item (a bucket, another mod's tank)
 * stores *the fluid*, exactly as [FluidGhostSlot] does and for the same reason - a fluid cannot be
 * carried on the cursor, so there is nothing else to click with; clicking with any other item
 * stores *that item*, as [GhostSlot] does; clicking empty-handed on a filled cell clears it. A
 * bucket therefore always means "the fluid in it", never "a bucket" - the same convention every
 * other fluid slot in the ecosystem uses, and the only one that leaves fluids reachable at all.
 *
 * Ghost in the same sense as both: the carried stack is inspected, never consumed or modified.
 *
 * @param onAmountScroll Told which way the wheel turned over a filled cell, for a caller that
 *   steps the amount by a notch - one of [scrollStepFor]'s, so the modifier keys held pick how
 *   coarse that notch is. Omit it for a cell with no amount to adjust.
 * @param onEditAmount Invoked on a **middle-click** over a filled cell, for a caller that opens
 *   [setAmountDialog]. The typed way to reach an amount the wheel would take a hundred notches to
 *   get to; omitted alongside [onAmountScroll] for the same reason.
 */
@Composable
fun ResourceGhostSlot(
	resource: ResourceComponent,
	carried: () -> ItemStack,
	onPlace: (ResourceComponent) -> Unit,
	onClear: () -> Unit,
	handleClick: (() -> Unit)? = null,
	amount: Long = 1,
	countText: String? = null,
	onAmountScroll: ((Int) -> Unit)? = null,
	onEditAmount: (() -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	// Whatever the resource's own kind has to say about it - an item's enchantments, lore and the
	// rest, a fluid's name and volume. Declared on the slot rather than reported to the screen: a
	// ghost slot is not a real one, so vanilla's own hovered-slot tooltip never sees it, and the
	// ones inside the filter card editor sit in a layer no host screen could track for them.
	var effectiveModifier = modifier.tooltip(tooltipFor(resource, amount))
	if (onAmountScroll != null) {
		effectiveModifier = effectiveModifier.onScroll<UINode> { _, event ->
			if (!resource.isBlank) onAmountScroll(event.scrollY.sign.toInt())
		}
	}
	// What the player is carrying, read as whichever registered kind claims it - a bucket of water
	// names the water, not the bucket. Kinds are asked in [displayKinds] order, which puts the item
	// kind last precisely because it claims any stack at all.
	fun place() {
		val held = carried()
		val claimed = ResourceKindRegistry.displayKinds()
			.firstNotNullOfOrNull { kind -> kind.display?.carriedIn(held) }
		when {
			claimed != null -> onPlace(claimed)
			!resource.isBlank -> onClear()
		}
	}
	Clickable(
		onClick = { place() },
		modifier = effectiveModifier,
		// The middle button is the amount gesture and the right one opens [handleClick]'s editor;
		// anything else still places or clears, as every button did when they all went to [onClick] -
		// vanilla's own ghost slots take either.
		onAuxClick = { _, button ->
			when {
				button == MouseButton.MIDDLE && !resource.isBlank -> onEditAmount?.invoke() ?: place()
				button == MouseButton.RIGHT && handleClick != null -> handleClick()
				else -> place()
			}
		},
	) { isHovered, _, _ ->
		val display = ResourceKindRegistry.forResource(resource)?.display
		if (display == null) FakeSlot(null, isHovered, countText)
		else display.SlotFace(resource, amount, isHovered, countText, enabled = true)
	}
}

/**
 * A [columns]-wide grid of [ResourceGhostSlot]s over [resources] (row-major) - the any-kind
 * equivalent of [GhostSlotGrid].
 */
@Composable
fun ResourceGhostSlotGrid(
	resources: List<ResourceComponent>,
	columns: Int,
	carried: () -> ItemStack,
	onPlace: (Int, ResourceComponent) -> Unit,
	onClear: (Int) -> Unit,
	handleClick: (Int) -> (() -> Unit)?,
	amounts: List<Long>? = null,
	/** Per-cell corner label. Omit it for [amountLabelFor] over the cell's own amount. */
	countText: ((Int) -> String?)? = null,
	onAmountScroll: ((Int, Int) -> Unit)? = null,
	onEditAmount: ((Int) -> Unit)? = null,
) {
	Column(verticalArrangement = Arrangement.spacedBy(0)) {
		resources.chunked(columns).forEachIndexed { rowIndex, row ->
			Row(horizontalArrangement = Arrangement.spacedBy(0)) {
				row.forEachIndexed { colIndex, resource ->
					val index = rowIndex * columns + colIndex
					val cellAmount = amounts?.getOrNull(index) ?: 1
					ResourceGhostSlot(
						resource = resource,
						carried = carried,
						onPlace = { onPlace(index, it) },
						onClear = { onClear(index) },
						handleClick = handleClick(index),
						amount = cellAmount,
						// Labelled unless the caller says otherwise, rather than unlabelled unless
						// the caller says so: a grid of cells holding measured resources wants their
						// amounts drawn, and leaving that to each caller is what left the pattern
						// terminal showing fluids and chemicals with no number at all. A caller that
						// passes [countText] keeps full control, nulls included - which is how
						// [StockingRowGrid] draws ∞, and how a caller whose amounts are already
						// authored avoids the second conversion this default would apply.
						countText = if (countText != null) countText(index) else amountLabelFor(resource, cellAmount),
						onAmountScroll = onAmountScroll?.let { callback -> { delta: Int -> callback(index, delta) } },
						onEditAmount = onEditAmount?.let { callback -> { callback(index) } },
					)
				}
			}
		}
	}
}

/**
 * How far one scroll notch moves an authored amount of [kind], given the modifier keys held.
 *
 * The kind's own [ResourceKind.authoredStep] is the unmodified notch and its
 * [ResourceKind.scrollSteps] are the three modified ones, so each kind brings the ladder that suits
 * how it is counted: 1 / 10 / 100 / 1000 mB for a fluid, 1 / 4 / 16 / 64 for items. Either way both
 * ends are reachable without a hundred notches - filling a multi-bucket cell, and correcting the
 * last millibucket of it.
 *
 * Never finer than `1`, which is what keeps a kind that declares no ladder of its own from
 * producing a scroll that moves nothing at all.
 */
internal fun scrollStepFor(kind: ResourceKind?): Long {
	if (kind == null) return 1L
	val steps = kind.scrollSteps
	val shift = Screen.hasShiftDown()
	val control = Screen.hasControlDown()
	return when {
		shift && control -> steps.shiftAndControl
		control -> steps.control
		shift -> steps.shift
		else -> kind.authoredStep
	}.coerceAtLeast(1L)
}

/**
 * The tooltip lines for [resource], or none at all for a blank cell - an empty ghost slot is a
 * placeholder, and naming it would be naming nothing.
 *
 * Asked of the resource's own kind, so a kind registered by an addon describes itself here with no
 * edit: see [net.kernelpanicsoft.boilerplate.client.ResourceDisplayKind.tooltipLines].
 */
internal fun tooltipFor(resource: ResourceComponent, amount: Long): List<Component> {
	if (resource.isBlank) return emptyList()
	val kind = ResourceKindRegistry.forResource(resource) ?: return listOf(resource.displayName())
	return kind.display?.tooltipLines(resource, amount) ?: listOf(resource.displayName())
}
