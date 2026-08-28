package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.builtins.ListSerializer
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.input.RadioGroup
import net.kernelpanicsoft.archie.gui.composables.input.RadioOption
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.network.ItemResourceSerializer
import net.kernelpanicsoft.boilerplate.network.OpenFilterCardEditorPacket
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardMenu
import net.kernelpanicsoft.boilerplate.pipe.gui.GhostSlotGrid
import net.kernelpanicsoft.boilerplate.pipe.gui.MiddleClickHandler
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * Aggregates [CombinedConditionState.children]'s own non-blank ghost slots by
 * [CombinedConditionState.operator] - each child is evaluated via [evaluateGhostSlot]
 * (recursively, for a nested [FilterCardItem]), so a combined card can nest arbitrarily deep.
 * [BooleanOperator.AND] on an all-blank child grid is defined as `false` (an empty "everything
 * must match" is vacuously true in boolean logic, but that would make a freshly-placed,
 * unconfigured combined card silently accept everything under a whitelist - `false` is the safer,
 * more obviously-"not configured yet" default).
 */
object CombinedConditionType : FilterConditionType<CombinedConditionState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "combined"

	override fun createState(): CombinedConditionState = CombinedConditionState()

	override fun matches(state: CombinedConditionState, context: FilterContext): Boolean {
		val results = state.children.filterNot { it.isBlank }.map { evaluateGhostSlot(it, context) }
		return when (state.operator) {
			BooleanOperator.AND -> results.isNotEmpty() && results.all { it }
			BooleanOperator.OR -> results.any { it }
		}
	}

	@Composable
	override fun Content(menu: FilterCardMenu, state: CombinedConditionState, middleClickHandler: MiddleClickHandler) {
		var operator by remember { mutableStateOf(state.operator) }
		var children by remember { mutableStateOf(state.children.toList()) }

		Text(Component.literal("Operator"), dropShadow = false)
		RadioGroup(
			options = listOf(
				RadioOption(BooleanOperator.AND, Component.literal("AND")),
				RadioOption(BooleanOperator.OR, Component.literal("OR")),
			),
			selected = operator,
			onSelected = { operator = it; state.operator = it; pushFieldUpdate(state::operator, it, BooleanOperatorSerializer) },
		)

		Text(Component.literal("Children"), dropShadow = false)
		GhostSlotGrid(
			resources = children,
			columns = 3,
			carried = { menu.carried },
			onPlace = { index, resource ->
				children = children.toMutableList().also { it[index] = resource }
				state.children[index] = resource
				pushFieldUpdate(state::children, children, ListSerializer(ItemResourceSerializer))
			},
			onClear = { index ->
				children = children.toMutableList().also { it[index] = ItemResource.BLANK }
				state.children[index] = ItemResource.BLANK
				pushFieldUpdate(state::children, children, ListSerializer(ItemResourceSerializer))
			},
			middleClickHandler = middleClickHandler,
			onMiddleClick = { index ->
				if (children[index].item !is FilterCardItem) null
				else ({
					BoilerplateNetworkChannel.toServer(
						OpenFilterCardEditorPacket(FilterCardTarget.ChildSlot(menu.target, index)),
					)
				})
			},
			// Only another filter card is meaningful here - evaluateGhostSlot's plain-item
			// identity fallback would silently "work" but defeats the point of a *combined*
			// condition, which exists to compose other conditions, not re-express a single item
			// match a plain ItemConditionType card already covers.
			filter = { it.item is FilterCardItem },
		)
	}
}
