package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import androidx.compose.runtime.*
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.gui.composables.basic.Label
import net.kernelpanicsoft.archie.gui.composables.input.RadioGroup
import net.kernelpanicsoft.archie.gui.composables.input.RadioOption
import net.kernelpanicsoft.archie.gui.composables.input.textfield.BasicTextField
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/** Matches [RegexConditionState.regex] against the resource's own registry id (`namespace:path`), e.g. `"^minecraft:.*_ingot$"`. An invalid pattern never matches, rather than throwing. */
object RegexConditionType : FilterConditionType<RegexConditionState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "regex"

	override fun createState(): RegexConditionState = RegexConditionState()

	override fun matches(state: RegexConditionState, context: FilterContext): Boolean {
		return state.regex.isNotBlank() && runCatching { Regex(state.regex).let { regex ->
			state.matcher.match(context).any { regex.containsMatchIn(it) }
		} }.getOrDefault(false)
	}

	@Composable
	override fun content(editor: FilterCardEditor, state: RegexConditionState) {
		var matcher by remember { mutableStateOf(state.matcher) }
		var regex by remember { mutableStateOf(state.regex) }

		Label(Component.literal("Regex Input"))
		RadioGroup(
			options = listOf(
				RadioOption(RegexInputMatcher.ID, Component.literal("Registry ID")),
				RadioOption(RegexInputMatcher.NAME, Component.literal("Display Name")),
				RadioOption(RegexInputMatcher.TAG, Component.literal("Tag Entry")),
			),
			selected = matcher,
			onSelected = { matcher = it; state.matcher = it; editor.push(state::matcher, it, RegexInputMatcherSerializer) },
		)

		Label(Component.literal("Regex"))
		BasicTextField(
			value = regex,
			onValueChange = { regex = it; state.regex = it; editor.push(state::regex, it, String.serializer()) },
			modifier = Modifier.width(FILTER_CARD_CONTENT_WIDTH),
		)
	}
}
