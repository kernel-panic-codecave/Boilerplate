package net.kernelpanicsoft.boilerplate.crafting

import net.kernelpanicsoft.archie.serialization.NBTHolder
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/**
 * A [Pattern], carried as an item so it can move through pipes/inventories/warehouses like
 * anything else, rather than living pinned to whichever block it was authored on - see
 * `docs/design/m4-crafting-automation.md`. Blank ([PatternItemData.pattern] unset) until encoded
 * at a Pattern Terminal, which consumes a blank stack and writes its result onto a fresh one;
 * held by a [net.kernelpanicsoft.boilerplate.crafting.PatternProviderHookState]'s own slots once
 * encoded. One item, not two - "blank" and "encoded" are just this item's own data-component state,
 * the same way an empty vs. written book share one item type.
 */
class PatternItem(properties: Properties) : Item(properties) {
	override fun getName(stack: ItemStack): Component {
		val output = PatternItemData(stack).pattern.outputs.firstOrNull() ?: return super.getName(stack)
		return Component.translatable(descriptionId).append(": ").append(output.resource.cachedStack.hoverName)
	}

	override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
		val pattern = PatternItemData(stack).pattern
		if (pattern == Pattern.EMPTY) {
			tooltip += Component.translatable("$descriptionId.blank")
			return
		}
		tooltip += Component.translatable(descriptionId + ".kind." + pattern.kind.name.lowercase())
		for (output in pattern.outputs) tooltip += Component.literal("${output.amount}x ").append(output.resource.cachedStack.hoverName)
	}
}

/**
 * [Pattern] data carried on a [PatternItem] stack - see [PatternItem]'s own KDoc.
 */
class PatternItemData(stack: ItemStack) : NBTHolder by NBTHolder.item(stack) {
	var pattern: Pattern by field(Pattern.serializer()) { Pattern.EMPTY }
}
