package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.ColorConditionState
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.ColorConditionType
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.CombinedConditionState
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.CombinedConditionType
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterConditionState
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterConditionType
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.ItemConditionState
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.ItemConditionType
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.ModConditionState
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.ModConditionType
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.RegexConditionState
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.RegexConditionType
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.TagConditionState
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.TagConditionType
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation

/** Registers Tubular Storage's [FilterConditionType]s into the custom registry [Registrars] declares - see [HookTypeRegistry]'s own identical shape. */
@Suppress("UNCHECKED_CAST")
object FilterConditionTypeRegistry : ADeferredRegistryHolder<FilterConditionType<out FilterConditionState>>(
	TubularStorage.MOD,
	Registrars.FILTER_CONDITION_TYPE.key() as ResourceKey<Registry<FilterConditionType<out FilterConditionState>>>,
) {
	val Item: FilterConditionType<ItemConditionState> by register(ItemConditionType.ID) { ItemConditionType }
	val Mod: FilterConditionType<ModConditionState> by register(ModConditionType.ID) { ModConditionType }
	val Tag: FilterConditionType<TagConditionState> by register(TagConditionType.ID) { TagConditionType }
	val Color: FilterConditionType<ColorConditionState> by register(ColorConditionType.ID) { ColorConditionType }
	val Regex: FilterConditionType<RegexConditionState> by register(RegexConditionType.ID) { RegexConditionType }
	val Combined: FilterConditionType<CombinedConditionState> by register(CombinedConditionType.ID) { CombinedConditionType }

	/** Looks up a registered [FilterConditionType] by its full id - see [HookTypeRegistry.byId]'s own KDoc for why this goes through the live registry rather than this holder's own bookkeeping map. */
	fun byId(id: ResourceLocation): FilterConditionType<FilterConditionState>? =
		Registrars.FILTER_CONDITION_TYPE.get(id) as? FilterConditionType<FilterConditionState>
}
