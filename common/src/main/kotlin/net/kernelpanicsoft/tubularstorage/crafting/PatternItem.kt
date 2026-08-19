package net.kernelpanicsoft.tubularstorage.crafting

import kotlinx.serialization.builtins.ListSerializer
import net.kernelpanicsoft.archie.serialization.NBTHolder
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.minecraft.Util
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/**
 * A [Pattern], carried as an item so it can move through pipes/inventories/warehouses like
 * anything else, rather than living pinned to whichever block it was authored on - see
 * `docs/design/m4-crafting-automation.md`. Blank ([PatternItemData.pattern] unset) until encoded
 * at a Pattern Terminal, which consumes a blank stack and writes its result onto a fresh one;
 * held by a [net.kernelpanicsoft.tubularstorage.crafting.PatternProviderHookState]'s own slots once
 * encoded. One item, not two - "blank" and "encoded" are just this item's own data-component state,
 * the same way an empty vs. written book share one item type.
 */
class PatternItem(properties: Properties) : Item(properties) {
	override fun getName(stack: ItemStack): Component {
		val output = PatternItemData(stack).pattern?.outputs?.firstOrNull() ?: return super.getName(stack)
		return Component.translatable(Util.makeDescriptionId("item", TubularStorage.MOD % "encoded_pattern")).append(": ").append(output.resource.cachedStack.hoverName)
	}

	override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
		val pattern = PatternItemData(stack).pattern
		if (pattern == null) {
			tooltip += Component.translatable(Util.makeDescriptionId("item", TubularStorage.MOD % "pattern") + ".blank")
			return
		}
		tooltip += Component.translatable(Util.makeDescriptionId("item", TubularStorage.MOD % "pattern") + ".kind." + pattern.kind.name.lowercase())
		for (output in pattern.outputs) tooltip += Component.literal("${output.amount}x ").append(output.resource.cachedStack.hoverName)
	}
}

/**
 * [Pattern] data carried on a [PatternItem] stack - see [PatternItem]'s own KDoc. Backed by a
 * 0-or-1-element list, not a nullable field directly - knbt's `AbstractNbtEncoder` doesn't support
 * `encodeNull` any more than it supports `encodeEnum` (the same latent gap [PatternKindSerializer]
 * already works around, hit again here), so a genuinely nullable serializer crashes the instant a
 * stack goes back to blank.
 */
class PatternItemData(stack: ItemStack) : NBTHolder by NBTHolder.item(stack) {
	private var patternList: List<Pattern> by field(ListSerializer(Pattern.serializer())) { emptyList() }

	var pattern: Pattern?
		get() = patternList.firstOrNull()
		set(value) { patternList = if (value == null) emptyList() else listOf(value) }
}
