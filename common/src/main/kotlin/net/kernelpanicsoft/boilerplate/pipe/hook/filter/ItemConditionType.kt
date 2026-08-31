package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.input.Checkbox
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.network.ItemResourceSerializer
import net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardMenu
import net.kernelpanicsoft.boilerplate.pipe.gui.GhostSlotGrid
import net.kernelpanicsoft.boilerplate.pipe.gui.ClickHandler
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * Matches any one of [ItemConditionState.itemMatches] - by plain item identity (the same bare
 * match a plain, non-card ghost slot already does), or, with
 * [ItemConditionState.matchComponents] on, also requiring the same data components (enchantments,
 * custom name, durability, ...) via [ItemResource.test] - the same [ItemResource.isBlank]-guarded
 * check the outer ghost grid already uses under the hood, just exposed as an explicit,
 * [FilterCardState.mode]-invertible, multi-item card.
 */
object ItemConditionType : FilterConditionType<ItemConditionState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "item"

	override fun createState(): ItemConditionState = ItemConditionState()

	override fun matches(state: ItemConditionState, context: FilterContext): Boolean {
		val entries = state.itemMatches.filterNot { it.isBlank }
		return if (state.matchComponents) {
			val stack = context.resource.toStack(1)
			entries.any { it.test(stack) }
		} else {
			entries.any { it.isOf(context.resource.item) }
		}
	}

	@Composable
	override fun content(menu: FilterCardMenu, state: ItemConditionState, clickHandler: ClickHandler) {
		var itemMatches by remember { mutableStateOf(state.itemMatches.toList()) }
		var matchComponents by remember { mutableStateOf(state.matchComponents) }

		Row(horizontalArrangement = Arrangement.spacedBy(4)) {
			Checkbox(
				checked = matchComponents,
				onCheckedChange = {
					matchComponents = it
					state.matchComponents = it
					pushFieldUpdate(state::matchComponents, it, Boolean.serializer())
				},
			)
			Text(Component.literal("Match components (enchantments, name, durability, ...)"), dropShadow = false)
		}

		Text(Component.literal("Items"), dropShadow = false)
		GhostSlotGrid(
			resources = itemMatches,
			columns = 3,
			carried = { menu.carried },
			onPlace = { index, resource ->
				itemMatches = itemMatches.toMutableList().also { it[index] = resource }
				state.itemMatches[index] = resource
				pushFieldUpdate(state::itemMatches, itemMatches, ListSerializer(ItemResourceSerializer))
			},
			onClear = { index ->
				itemMatches = itemMatches.toMutableList().also { it[index] = ItemResource.BLANK }
				state.itemMatches[index] = ItemResource.BLANK
				pushFieldUpdate(state::itemMatches, itemMatches, ListSerializer(ItemResourceSerializer))
			},
			clickHandler = clickHandler,
			handleClick = { null },
		)
	}
}
