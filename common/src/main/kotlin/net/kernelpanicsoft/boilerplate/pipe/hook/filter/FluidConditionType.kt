package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import androidx.compose.runtime.*
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.gui.composables.basic.Label
import net.kernelpanicsoft.archie.gui.composables.input.Checkbox
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.network.FluidResourceSerializer
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.pipe.gui.ClickHandler
import net.kernelpanicsoft.boilerplate.pipe.gui.FluidGhostSlotGrid
import net.kernelpanicsoft.boilerplate.util.test
import net.kernelpanicsoft.boilerplate.util.toFluidStack
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * Matches any one of the fluids named in [FluidConditionState.fluidMatches] - the fluid counterpart
 * of [ItemConditionType], and the one filter condition that can name a fluid outright rather than
 * describing it by mod, tag or pattern.
 *
 * Membership is compared through [ResourceIdentity], never with `==`: `FluidResource` overrides
 * neither `equals` nor `hashCode` (Common Storage Lib 0.0.5), so a direct comparison against a
 * freshly deserialised resource never matches and the card would silently reject everything.
 */
object FluidConditionType : FilterConditionType<FluidConditionState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "fluid"

	override fun createState(): FluidConditionState = FluidConditionState()

	override fun matches(state: FluidConditionState, context: FilterContext): Boolean {
		val tested = context.resource as? FluidResource ?: return false
		return if (state.matchComponents) {
			val stack = tested.toFluidStack(1)
			state.fluidMatches.any { it.test(stack) }
		} else {
			state.fluidMatches.any { it.isOf(tested.type) }
		}
	}

	@Composable
	override fun content(editor: FilterCardEditor, state: FluidConditionState, clickHandler: ClickHandler) {
		var fluidMatches by remember { mutableStateOf(state.fluidMatches.toList()) }
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

		Label(Component.literal("Fluids"))
		FluidGhostSlotGrid(
			resources = fluidMatches,
			columns = 3,
			carried = { editor.carried() },
			onPlace = { index, resource ->
				fluidMatches = fluidMatches.toMutableList().also { it[index] = resource }
				state.fluidMatches[index] = resource
				editor.push(state::fluidMatches, fluidMatches, ListSerializer(FluidResourceSerializer))
			},
			onClear = { index ->
				fluidMatches = fluidMatches.toMutableList().also { it[index] = FluidResource.BLANK }
				state.fluidMatches[index] = FluidResource.BLANK
				editor.push(state::fluidMatches, fluidMatches, ListSerializer(FluidResourceSerializer))
			},
		)
	}
}
