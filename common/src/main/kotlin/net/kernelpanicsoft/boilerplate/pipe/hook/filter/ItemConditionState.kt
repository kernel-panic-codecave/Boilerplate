package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.boilerplate.network.ItemResourceSerializer
import net.kernelpanicsoft.boilerplate.network.SItemResource

/** [ItemConditionType]'s own state: a ghost grid of items it matches any one of. */
class ItemConditionState : FilterConditionState(ItemConditionType.ID) {
	val itemMatches: MutableList<SItemResource> by editableListField(ItemResourceSerializer) { List(SLOTS) { ItemResource.BLANK } }

	/** Whether a match also requires the same data components (enchantments, custom name, durability, ...), not just the same base item - see [ItemConditionType.matches]. */
	var matchComponents: Boolean by editableField(Boolean.serializer()) { false }

	companion object {
		/** Matches the outer hook filter grid's own 3x3 - "at least a 3x3", not a single reference. */
		const val SLOTS = 9
	}
}
