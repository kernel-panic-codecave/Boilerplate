package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.data.common.tags.platform.ACommonTags
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.Item

object TagsRegistry
{
	fun init() {
		Items.init()
	}
	object Items : ACommonTags.Tags<Item>(Registries.ITEM)
	{
		internal fun init() = Unit
		val TOOLS_WRENCH = tag("tools/wrench")
	}
}