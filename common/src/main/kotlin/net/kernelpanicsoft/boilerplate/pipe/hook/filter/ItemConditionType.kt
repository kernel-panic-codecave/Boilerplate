package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import androidx.compose.runtime.*
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.gui.composables.basic.Label
import net.kernelpanicsoft.archie.gui.composables.input.Checkbox
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.network.ItemResourceSerializer
import net.kernelpanicsoft.boilerplate.pipe.gui.ClickHandler
import net.kernelpanicsoft.boilerplate.pipe.gui.GhostSlotGrid
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

	/**
	 * Item-only by nature: this card names concrete items in a ghost grid, so a resource of any
	 * other kind simply isn't something it can express and never matches. A fluid is filtered by
	 * [ModConditionType]/[TagConditionType]/[RegexConditionType] - all of which are kind-agnostic -
	 * until a fluid ghost grid exists to state one directly.
	 */
	override fun matches(state: ItemConditionState, context: FilterContext): Boolean {
		val tested = context.resource as? ItemResource ?: return false
		val entries = state.itemMatches.filterNot { it.isBlank }
		return if (state.matchComponents) {
			val stack = tested.toStack(1)
			entries.any { it.test(stack) }
		} else {
			entries.any { it.isOf(tested.item) }
		}
	}

	@Composable
	override fun content(editor: FilterCardEditor, state: ItemConditionState, clickHandler: ClickHandler) {
		var itemMatches by remember { mutableStateOf(state.itemMatches.toList()) }
		var matchComponents by remember { mutableStateOf(state.matchComponents) }

		Row(horizontalArrangement = Arrangement.spacedBy(4), verticalAlignment = Alignment.CenterVertically) {
			Checkbox(
				checked = matchComponents,
				onCheckedChange = {
					matchComponents = it
					state.matchComponents = it
					editor.push(state::matchComponents, it, Boolean.serializer())
				},
			)
			Label(Component.literal("Match components"))
		}

		Label(Component.literal("Items"))
		GhostSlotGrid(
			resources = itemMatches,
			columns = 3,
			carried = { editor.carried() },
			onPlace = { index, resource ->
				itemMatches = itemMatches.toMutableList().also { it[index] = resource }
				state.itemMatches[index] = resource
				editor.push(state::itemMatches, itemMatches, ListSerializer(ItemResourceSerializer))
			},
			onClear = { index ->
				itemMatches = itemMatches.toMutableList().also { it[index] = ItemResource.BLANK }
				state.itemMatches[index] = ItemResource.BLANK
				editor.push(state::itemMatches, itemMatches, ListSerializer(ItemResourceSerializer))
			},
			clickHandler = clickHandler,
			handleClick = { null },
		)
	}
}
