package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.input.textfield.BasicTextField
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardMenu
import net.kernelpanicsoft.boilerplate.pipe.gui.ClickHandler
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/** Matches [RegexConditionState.regex] against the resource's own registry id (`namespace:path`), e.g. `"^minecraft:.*_ingot$"`. An invalid pattern never matches, rather than throwing. */
object RegexConditionType : FilterConditionType<RegexConditionState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "regex"

	override fun createState(): RegexConditionState = RegexConditionState()

	override fun matches(state: RegexConditionState, context: FilterContext): Boolean {
		if (state.regex.isBlank()) return false
		val id = ResourceKindRegistry.forResource(context.resource)?.registryId(context.resource)?.toString() ?: return false
		return runCatching { Regex(state.regex).containsMatchIn(id) }.getOrDefault(false)
	}

	@Composable
	override fun content(menu: FilterCardMenu, state: RegexConditionState, clickHandler: ClickHandler) {
		var regex by remember { mutableStateOf(state.regex) }

		Text(Component.literal("Regex"), dropShadow = false)
		BasicTextField(
			value = regex,
			onValueChange = { regex = it; state.regex = it; pushFieldUpdate(state::regex, it, String.serializer()) },
			modifier = Modifier.width(FILTER_CARD_CONTENT_WIDTH),
		)
	}
}
