package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import kotlinx.serialization.builtins.serializer

/** [ModConditionType]'s own state: the namespace it matches, e.g. `"minecraft"`. */
class ModConditionState : FilterConditionState(ModConditionType.ID) {
	var modId: String by editableField(String.serializer()) { "" }
}
