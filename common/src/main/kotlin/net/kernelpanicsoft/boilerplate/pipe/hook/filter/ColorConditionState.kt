package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import kotlinx.serialization.builtins.nullable
import net.kernelpanicsoft.boilerplate.pipe.entity.DyeColorSerializer
import net.minecraft.world.item.DyeColor

/** [ColorConditionType]'s own state: the consignment color it matches. */
class ColorConditionState : FilterConditionState(ColorConditionType.ID) {
	var color: DyeColor? by editableField(DyeColorSerializer.nullable) { null }
}
