package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.serialization.NBTHolder
import net.kernelpanicsoft.archie.serialization.serializers.ResourceLocationSerializer
import net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry
import net.minecraft.resources.ResourceLocation

/**
 * Self-contained, per-face persisted state for one attached [PipeHookType] - each concrete hook
 * type ([ExtractionHookState], [SortingHookState]) owns its own [NBTHolder]-backed subclass with
 * only the fields it actually needs, rather than every hook kind sharing one flat shape.
 *
 * [type] is a normal declared field like any other, not special-cased - so
 * [net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity.hooks]'s nested-map factory can
 * read it directly off a saved entry's own raw sub-tag (before any typed holder exists to read it
 * through) to decide which concrete subclass to reconstruct.
 */
abstract class HookHolderState(defaultType: ResourceLocation) : NBTHolder by NBTHolder.create() {
	val type: ResourceLocation by field(ResourceLocationSerializer) { defaultType }
	val fromRegistry get() = HookTypeRegistry.byId(type)
}
