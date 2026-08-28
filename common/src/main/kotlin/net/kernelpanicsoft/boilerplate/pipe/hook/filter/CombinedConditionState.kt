package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.boilerplate.network.ItemResourceSerializer
import net.kernelpanicsoft.boilerplate.network.SItemResource

/** [CombinedConditionType]'s own state: how [children] combine, and the ghost grid itself - see its own KDoc. */
class CombinedConditionState : FilterConditionState(CombinedConditionType.ID) {
	var operator: BooleanOperator by editableField(BooleanOperatorSerializer) { BooleanOperator.AND }

	/** A nested ghost grid, each entry either blank, a plain item (identity match), or another [FilterCardItem] (recursively [evaluateGhostSlot]'d). */
	val children: MutableList<SItemResource> by editableListField(ItemResourceSerializer) { List(CHILD_SLOTS) { ItemResource.BLANK } }

	companion object {
		/** Matches the outer hook filter grid's own 3x3 for visual consistency. */
		const val CHILD_SLOTS = 9
	}
}
