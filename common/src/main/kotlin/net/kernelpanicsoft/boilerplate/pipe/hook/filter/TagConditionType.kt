package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.architectury.extensions.injected.InjectedRegistryEntryExtension
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.input.textfield.BasicTextField
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.registries.holder
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardMenu
import net.kernelpanicsoft.boilerplate.pipe.gui.MiddleClickHandler
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item

/** Matches every item in [TagConditionState.tagId]'s item tag, e.g. `"minecraft:logs"`. An unparsable/unset id never matches, rather than throwing. */
object TagConditionType : FilterConditionType<TagConditionState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "tag"

	override fun createState(): TagConditionState = TagConditionState()

	override fun matches(state: TagConditionState, context: FilterContext): Boolean {
		val location = runCatching { ResourceLocation.parse(state.tagId) }.getOrNull() ?: return false
		return (context.resource.item as InjectedRegistryEntryExtension<Item>).holder.`is`(TagKey.create(Registries.ITEM, location))
	}

	@Composable
	override fun Content(menu: FilterCardMenu, state: TagConditionState, middleClickHandler: MiddleClickHandler) {
		var tagId by remember { mutableStateOf(state.tagId) }

		Text(Component.literal("Tag ID"), dropShadow = false)
		BasicTextField(
			value = tagId,
			onValueChange = { tagId = it; state.tagId = it; pushFieldUpdate(state::tagId, it, String.serializer()) },
			modifier = Modifier.width(FILTER_CARD_CONTENT_WIDTH),
		)
	}
}
