package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import androidx.compose.runtime.*
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.builtins.ListSerializer
import net.kernelpanicsoft.archie.gui.composables.basic.Label
import net.kernelpanicsoft.archie.gui.composables.input.RadioGroup
import net.kernelpanicsoft.archie.gui.composables.input.RadioOption
import net.kernelpanicsoft.archie.gui.layer.LocalLayerManager
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.gui.GhostSlotGrid
import net.kernelpanicsoft.boilerplate.pipe.gui.filterCardEditor
import net.kernelpanicsoft.boilerplate.resource.ItemResourceSerializer
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
	override fun content(editor: FilterCardEditor, state: CombinedConditionState) {
		val layers = LocalLayerManager.current
		var operator by remember { mutableStateOf(state.operator) }
		var children by remember { mutableStateOf(state.children.toList()) }

		Label(Component.literal("Operator"))
		RadioGroup(
			options = listOf(
				RadioOption(BooleanOperator.AND, Component.literal("AND")),
				RadioOption(BooleanOperator.OR, Component.literal("OR")),
			),
			selected = operator,
			onSelected = { operator = it; state.operator = it; editor.push(state::operator, it, BooleanOperatorSerializer) },
		)

		Label(Component.literal("Children"))
		GhostSlotGrid(
			resources = children,
			columns = 3,
			carried = { editor.carried() },
			onPlace = { index, resource ->
				children = children.toMutableList().also { it[index] = resource }
				state.children[index] = resource
				editor.push(state::children, state.children.toList(), ListSerializer(ItemResourceSerializer))
			},
			onClear = { index ->
				children = children.toMutableList().also { it[index] = ItemResource.BLANK }
				state.children[index] = ItemResource.BLANK
				editor.push(state::children, state.children.toList(), ListSerializer(ItemResourceSerializer))
			},
			handleRightClick = { index ->
				if (children[index].item !is FilterCardItem) null
				else ({ layers.filterCardEditor(FilterCardTarget.ChildSlot(editor.target, index), editor.carried) })
			},
			filter = { it.item is FilterCardItem },
		)
	}
}
