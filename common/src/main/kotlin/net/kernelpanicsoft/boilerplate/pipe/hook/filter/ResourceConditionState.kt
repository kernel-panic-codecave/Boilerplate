package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.boilerplate.resource.ResourceComponentSerializer
import net.kernelpanicsoft.boilerplate.resource.SResourceComponent

/** [ResourceConditionType]'s own state: a ghost grid of resources it matches any one of, of any kind. */
class ResourceConditionState : FilterConditionState(ResourceConditionType.ID) {
	/**
	 * Stored through [ResourceComponentSerializer], which tags each entry with its kind - so one grid
	 * holds an item, a fluid and a chemical side by side and each persists as itself.
	 */
	val resourceMatches: MutableList<SResourceComponent> by editableListField(ResourceComponentSerializer) { List(SLOTS) { ItemResource.BLANK } }

	/**
	 * Whether a match also requires everything a resource carries beyond its identity - an item's
	 * data components, a fluid's data patch - or only that it is the same item or fluid.
	 *
	 * Answered per kind through [net.kernelpanicsoft.boilerplate.resource.ResourceKind.baseIdentityOf],
	 * so it means the right thing for each and simply makes no difference to a kind that carries
	 * nothing beyond its identity.
	 */
	var matchComponents: Boolean by editableField(Boolean.serializer()) { false }

	companion object {
		/** Matches the outer hook filter grid's own 3x3 - "at least a 3x3", not a single reference. */
		const val SLOTS = 9
	}
}
