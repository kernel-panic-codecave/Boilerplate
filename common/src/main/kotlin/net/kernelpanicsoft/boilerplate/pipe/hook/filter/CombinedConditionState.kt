package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.boilerplate.network.ItemResourceSerializer
import net.kernelpanicsoft.boilerplate.network.SItemResource

/** [CombinedConditionType]'s own state: how [children] combine, and the ghost grid itself - see its own KDoc. */
class CombinedConditionState : FilterConditionState(CombinedConditionType.ID) {
	var operator: BooleanOperator by editableField(BooleanOperator.serializer()) { BooleanOperator.AND }

	/** A nested ghost grid, each entry either blank, or another [FilterCardItem] (recursively [evaluateGhostSlot]'d). */
	val children: MutableList<SItemResource> by editableListField(ItemResourceSerializer) { List(CHILD_SLOTS) { ItemResource.BLANK } }

	companion object {
		const val CHILD_SLOTS = 9
	}
}
