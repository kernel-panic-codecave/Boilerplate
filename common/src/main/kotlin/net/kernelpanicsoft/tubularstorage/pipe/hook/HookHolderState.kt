package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.serialization.serializers.ResourceLocationSerializer
import net.kernelpanicsoft.tubularstorage.pipe.attachment.AttachmentHolderState
import net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry
import net.minecraft.resources.ResourceLocation

/**
 * Self-contained, per-face persisted state for one attached [PipeHookType] - each concrete hook
 * type ([ExtractionHookState], [SortingHookState]) owns its own subclass with only the fields it
 * actually needs, rather than every hook kind sharing one flat shape. Extends the shared
 * [AttachmentHolderState] (which owns the [type] field) with the hook registry's own lookup.
 */
abstract class HookHolderState(defaultType: ResourceLocation) : AttachmentHolderState(defaultType) {
	val fromRegistry get() = HookTypeRegistry.byId(type)
}
