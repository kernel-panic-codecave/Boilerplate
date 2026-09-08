package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import androidx.compose.runtime.*
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.gui.composables.basic.Label
import net.kernelpanicsoft.archie.gui.composables.input.textfield.BasicTextField
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.gui.ClickHandler
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/** Matches every resource registered under [ModConditionState.modId]'s namespace, e.g. `"minecraft"` or `"create"` - items and fluids alike, through [ResourceKindRegistry]. */
object ModConditionType : FilterConditionType<ModConditionState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "mod"

	override fun createState(): ModConditionState = ModConditionState()

	override fun matches(state: ModConditionState, context: FilterContext): Boolean =
		state.modId.isNotBlank() && ResourceKindRegistry.forResource(context.resource)?.registryId(context.resource)?.namespace == state.modId

	@Composable
	override fun content(editor: FilterCardEditor, state: ModConditionState, clickHandler: ClickHandler) {
		var modId by remember { mutableStateOf(state.modId) }

		Label(Component.literal("Mod ID"))
		BasicTextField(
			value = modId,
			onValueChange = { modId = it; state.modId = it; editor.push(state::modId, it, String.serializer()) },
			modifier = Modifier.width(FILTER_CARD_CONTENT_WIDTH),
		)
	}
}
