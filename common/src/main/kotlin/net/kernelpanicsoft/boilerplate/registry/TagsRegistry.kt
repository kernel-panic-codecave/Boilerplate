package net.kernelpanicsoft.boilerplate.registry

import net.kernelpanicsoft.archie.data.common.tags.platform.ACommonTags
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block

object TagsRegistry
{
	fun init() {
		Blocks.init()
		Items.init()
	}

	/**
	 * Boilerplate's own block tags. [MINEABLE_WRENCH] is what makes the wrench the mod's
	 * harvesting tool: every player-facing block joins it and requires the correct tool for drops,
	 * and [ItemRegistry.Wrench]'s own tool-component rule is written against exactly this tag - so
	 * a wrong tool still mines, just slowly and without drops.
	 */
	object Blocks : ACommonTags.Tags<Block>(Registries.BLOCK)
	{
		internal fun init() = Unit

		val MINEABLE_WRENCH = tag( "mineable/wrench")
	}

	object Items : ACommonTags.Tags<Item>(Registries.ITEM)
	{
		internal fun init() = Unit

		/** The cross-mod convention tag for wrench items - [ItemRegistry.Wrench] joins it so other mods' wrench-integrating recipes/features see it as one. */
		val TOOLS_WRENCH = tag("tools/wrench")
	}
}
