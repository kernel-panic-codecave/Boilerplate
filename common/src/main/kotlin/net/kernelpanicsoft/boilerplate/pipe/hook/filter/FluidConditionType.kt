package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import kotlinx.serialization.builtins.ListSerializer
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.network.FluidResourceSerializer
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.pipe.gui.ClickHandler
import net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardMenu
import net.kernelpanicsoft.boilerplate.pipe.gui.FluidGhostSlotGrid
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
		val testedIdentity = ResourceIdentity.of(tested)
		return state.fluidMatches.any { !it.isBlank && ResourceIdentity.of(it) == testedIdentity }
	}

	@Composable
	override fun content(menu: FilterCardMenu, state: FluidConditionState, clickHandler: ClickHandler) {
		var fluidMatches by remember { mutableStateOf(state.fluidMatches.toList()) }

		Text(Component.literal("Fluids (click a slot holding a bucket or tank)"), dropShadow = false)
		FluidGhostSlotGrid(
			resources = fluidMatches,
			columns = 3,
			carried = { menu.carried },
			onPlace = { index, resource ->
				fluidMatches = fluidMatches.toMutableList().also { it[index] = resource }
				state.fluidMatches[index] = resource
				pushFieldUpdate(state::fluidMatches, fluidMatches, ListSerializer(FluidResourceSerializer))
			},
			onClear = { index ->
				fluidMatches = fluidMatches.toMutableList().also { it[index] = FluidResource.BLANK }
				state.fluidMatches[index] = FluidResource.BLANK
				pushFieldUpdate(state::fluidMatches, fluidMatches, ListSerializer(FluidResourceSerializer))
			},
		)
	}
}
