package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.serialization.builtins.nullable
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.Scrollable
import net.kernelpanicsoft.archie.gui.composables.input.RadioGroup
import net.kernelpanicsoft.archie.gui.composables.input.RadioOption
import net.kernelpanicsoft.archie.gui.composables.theme.TextureStates
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.height
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.archie.gui.theme.SimpleThemeState
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.entity.DyeColorSerializer
import net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardMenu
import net.kernelpanicsoft.boilerplate.pipe.gui.MiddleClickHandler
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.DyeColor

/**
 * Matches by [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem.color], the same
 * consignment color a plain [net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule.color]
 * already filters push-routing by - a card lets that same check live inside a combined tree
 * (`"item X AND color red"`) instead of only ever being a hook-wide setting. [FilterContext.color]
 * is only ever populated on the push-routing side ([net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter]) -
 * a standing request has no consignment color, so this never matches there.
 */
object ColorConditionType : FilterConditionType<ColorConditionState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "color"

	override fun createState(): ColorConditionState = ColorConditionState()

	override fun matches(state: ColorConditionState, context: FilterContext): Boolean =
		state.color != null && context.color == state.color

	@Composable
	override fun Content(menu: FilterCardMenu, state: ColorConditionState, middleClickHandler: MiddleClickHandler) {
		var color by remember { mutableStateOf(state.color) }

		Text(Component.literal("Color"), dropShadow = false)
		val theme = LocalTheme.current
		val composableTheme = theme.getComposableTheme("radio")
		val default = composableTheme.states[TextureStates.DEFAULT] as SimpleThemeState
		Scrollable(modifier = Modifier.height(default.height)) {
			RadioGroup<DyeColor?>(
				options = buildList {
					add(RadioOption(null, Component.literal("Any")))
					for (dyeColor in DyeColor.entries) add(RadioOption(dyeColor, Component.literal(dyeColor.name.lowercase())))
				},
				selected = color,
				onSelected = { color = it; state.color = it; pushFieldUpdate(state::color, it, DyeColorSerializer.nullable) },
			)
		}
	}
}
