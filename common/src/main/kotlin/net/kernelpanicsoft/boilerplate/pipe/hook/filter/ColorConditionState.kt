package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import kotlinx.serialization.builtins.nullable
import net.kernelpanicsoft.boilerplate.util.DyeColorSerializer
import net.kernelpanicsoft.boilerplate.util.SDyeColor

/** [ColorConditionType]'s own state: the consignment color it matches. */
class ColorConditionState : FilterConditionState(ColorConditionType.ID) {
	var color: SDyeColor? by editableField(DyeColorSerializer.nullable) { null }
}
