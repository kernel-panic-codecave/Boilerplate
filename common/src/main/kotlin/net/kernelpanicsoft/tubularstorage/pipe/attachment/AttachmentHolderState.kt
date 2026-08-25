package net.kernelpanicsoft.tubularstorage.pipe.attachment

import net.kernelpanicsoft.archie.serialization.NBTHolder
import net.kernelpanicsoft.archie.serialization.serializers.ResourceLocationSerializer
import net.minecraft.resources.ResourceLocation

/**
 * Self-contained persisted state for one attachment of a [PipeAttachmentType] - the shared base
 * behind [net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState] (one per attached face)
 * and [net.kernelpanicsoft.tubularstorage.pipe.encasement.EncasementHolderState] (the whole-segment
 * wrapper). Carries only what every attachment shares: the [type] id naming which registry entry
 * built it.
 *
 * [type] is a normal declared field like any other, not special-cased - so the nested-map
 * factories on [net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity] can read it
 * directly off a saved entry's own raw sub-tag (before any typed holder exists to read it through)
 * to decide which concrete subclass to reconstruct.
 */
abstract class AttachmentHolderState(defaultType: ResourceLocation) : NBTHolder by NBTHolder.create() {
	val type: ResourceLocation by field(ResourceLocationSerializer) { defaultType }
}
