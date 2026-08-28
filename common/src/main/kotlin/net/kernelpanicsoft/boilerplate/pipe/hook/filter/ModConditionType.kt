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
import net.kernelpanicsoft.boilerplate.pipe.gui.MiddleClickHandler
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/** Matches every item registered under [ModConditionState.modId]'s namespace, e.g. `"minecraft"` or `"create"`. */
object ModConditionType : FilterConditionType<ModConditionState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "mod"

	override fun createState(): ModConditionState = ModConditionState()

	override fun matches(state: ModConditionState, context: FilterContext): Boolean =
		state.modId.isNotBlank() && (BuiltInRegistries.ITEM.getKey(context.resource.item) as ResourceLocation?)?.namespace == state.modId

	@Composable
	override fun Content(menu: FilterCardMenu, state: ModConditionState, middleClickHandler: MiddleClickHandler) {
		var modId by remember { mutableStateOf(state.modId) }

		Text(Component.literal("Mod ID"), dropShadow = false)
		BasicTextField(
			value = modId,
			onValueChange = { modId = it; state.modId = it; pushFieldUpdate(state::modId, it, String.serializer()) },
			modifier = Modifier.width(FILTER_CARD_CONTENT_WIDTH),
		)
	}
}
