package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import androidx.compose.runtime.*
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.gui.composables.basic.Label
import net.kernelpanicsoft.archie.gui.composables.input.textfield.BasicTextField
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * Matches every item in [TagConditionState.tagId]'s item tag, e.g. `"minecraft:logs"`. A trailing
 * `*` segment widens the match to every item tag sharing the id's prefix - e.g. `"c:shards"` plus a
 * trailing `*` matches any `"c:shards/<metal>"` tag, so mods that only tag per metal (never a root
 * `"c:shards"` tag) are still addressable. An unparsable/unset id (or a wildcard matching nothing)
 * never matches, rather than throwing.
 */
object TagConditionType : FilterConditionType<TagConditionState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "tag"

	override fun createState(): TagConditionState = TagConditionState()

	override fun matches(state: TagConditionState, context: FilterContext): Boolean {
		val tagId = state.tagId
		if (tagId.isEmpty()) return false
		// The kind supplies its own tags, so an item card matches item tags and a fluid card fluid
		// tags without this having to know which registry either lives in - see [ResourceKind.tagsOf].
		val tags = ResourceKindRegistry.forResource(context.resource)?.tagsOf(context.resource).orEmpty()
		if ('*' !in tagId) {
			val location = runCatching { ResourceLocation.parse(tagId) }.getOrNull() ?: return false
			return tags.any { it.location == location }
		}
		val regex = Regex(tagId.split('*').joinToString(".*") { Regex.escape(it) })
		return tags.any { regex.matches(it.location.toString()) }
	}

	@Composable
	override fun content(editor: FilterCardEditor, state: TagConditionState) {
		var tagId by remember { mutableStateOf(state.tagId) }

		Label(Component.literal("Tag ID"))
		BasicTextField(
			value = tagId,
			onValueChange = { tagId = it; state.tagId = it; editor.push(state::tagId, it, String.serializer()) },
			modifier = Modifier.width(FILTER_CARD_CONTENT_WIDTH),
		)
	}
}
