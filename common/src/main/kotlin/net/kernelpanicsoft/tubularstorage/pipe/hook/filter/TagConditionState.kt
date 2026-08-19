package net.kernelpanicsoft.tubularstorage.pipe.hook.filter

import kotlinx.serialization.builtins.serializer

/** [TagConditionType]'s own state: the item tag id it matches, e.g. `"minecraft:logs"`. */
class TagConditionState : FilterConditionState(TagConditionType.ID) {
	var tagId: String by editableField(String.serializer()) { "" }
}
