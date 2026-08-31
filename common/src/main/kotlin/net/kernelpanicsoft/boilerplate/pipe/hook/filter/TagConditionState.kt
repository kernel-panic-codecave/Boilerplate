package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import kotlinx.serialization.builtins.serializer

/** [TagConditionType]'s own state: the item tag id it matches, e.g. `"minecraft:logs"`, or `"c:shards"` with a trailing `*` segment for widened tag matching. */
class TagConditionState : FilterConditionState(TagConditionType.ID) {
	var tagId: String by editableField(String.serializer()) { "" }
}
