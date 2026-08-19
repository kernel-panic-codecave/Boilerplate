package net.kernelpanicsoft.tubularstorage.pipe.hook.filter

import kotlinx.serialization.builtins.nullable
import net.kernelpanicsoft.tubularstorage.pipe.entity.DyeColorSerializer
import net.minecraft.world.item.DyeColor

/** [ColorConditionType]'s own state: the consignment color it matches. */
class ColorConditionState : FilterConditionState(ColorConditionType.ID) {
	var color: DyeColor? by editableField(DyeColorSerializer.nullable) { null }
}
