package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.composables.input.Clickable
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.input.onScroll
import net.kernelpanicsoft.archie.gui.nodes.UINode
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
 */
@Composable
fun ResourceGhostSlot(
	resource: ResourceComponent,
	carried: () -> ItemStack,
	onPlace: (ResourceComponent) -> Unit,
	onClear: () -> Unit,
	clickHandler: ClickHandler,
	handleClick: (() -> Unit)? = null,
	amount: Long = 1,
	countText: String? = null,
	onAmountScroll: ((Int) -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	var effectiveModifier = modifier
	if (onAmountScroll != null) {
		effectiveModifier = effectiveModifier.onScroll<UINode> { _, event ->
			if (!resource.isBlank) onAmountScroll(event.scrollY.sign.toInt())
		}
	}
	Clickable(
		onClick = {
			val held = carried()
			val heldFluid = fluidIn(held)
			when {
				heldFluid != null -> onPlace(heldFluid)
				!held.isEmpty -> onPlace(ItemResource.of(held))
				!resource.isBlank -> onClear()
			}
		},
		modifier = effectiveModifier,
	) { isHovered, _, _ ->
		LaunchedEffect(isHovered, resource, handleClick) {
			clickHandler.setHovered(if (isHovered) handleClick else null)
		}
		when (resource) {
			is FluidResource -> FluidSlotFace(resource, isHovered)
			is ItemResource -> FakeSlot(if (resource.isBlank) null else ResourceStack(resource, amount), isHovered, countText)
			else -> FakeSlot(null, isHovered, countText)
		}
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
	clickHandler: ClickHandler,
	handleClick: (Int) -> (() -> Unit)?,
	amounts: List<Long>? = null,
	countText: ((Int) -> String?)? = null,
	onAmountScroll: ((Int, Int) -> Unit)? = null,
) {
	Column(verticalArrangement = Arrangement.spacedBy(0)) {
		resources.chunked(columns).forEachIndexed { rowIndex, row ->
			Row(horizontalArrangement = Arrangement.spacedBy(0)) {
				row.forEachIndexed { colIndex, resource ->
					val index = rowIndex * columns + colIndex
					ResourceGhostSlot(
						resource = resource,
						carried = carried,
						onPlace = { onPlace(index, it) },
						onClear = { onClear(index) },
						clickHandler = clickHandler,
						handleClick = handleClick(index),
						amount = amounts?.getOrNull(index) ?: 1,
						countText = countText?.invoke(index),
						onAmountScroll = onAmountScroll?.let { callback -> { delta: Int -> callback(index, delta) } },
					)
				}
			}
		}
	}
}
