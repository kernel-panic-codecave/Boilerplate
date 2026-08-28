package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import kotlinx.serialization.builtins.serializer

/** [RegexConditionType]'s own state: the pattern it matches against a resource's registry id. */
class RegexConditionState : FilterConditionState(RegexConditionType.ID) {
	var regex: String by editableField(String.serializer()) { "" }
}
