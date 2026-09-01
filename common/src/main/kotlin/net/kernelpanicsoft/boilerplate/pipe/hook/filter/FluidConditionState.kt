package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import net.kernelpanicsoft.boilerplate.network.FluidResourceSerializer
import net.kernelpanicsoft.boilerplate.network.SFluidResource

/** [FluidConditionType]'s own state: a ghost grid of fluids it matches any one of - the fluid counterpart of [ItemConditionState]. */
class FluidConditionState : FilterConditionState(FluidConditionType.ID) {
	val fluidMatches: MutableList<SFluidResource> by editableListField(FluidResourceSerializer) { List(SLOTS) { FluidResource.BLANK } }

	companion object {
		/** Matches [ItemConditionState.SLOTS] so the two cards read as siblings in the editor. */
		const val SLOTS = 9
	}
}
