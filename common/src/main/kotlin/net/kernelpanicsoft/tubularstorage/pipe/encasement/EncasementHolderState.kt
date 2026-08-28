package net.kernelpanicsoft.tubularstorage.pipe.encasement

import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.tubularstorage.pipe.attachment.AttachmentHolderState
import net.kernelpanicsoft.tubularstorage.registry.EncasementTypeRegistry
import net.minecraft.resources.ResourceLocation

/**
 * Self-contained persisted state for the [PipeEncasementType] wrapping one pipe segment - the
 * whole-segment counterpart to [net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState],
 * with each concrete encasement type owning its own subclass carrying only the fields it needs.
 * Extends the shared [AttachmentHolderState] (which owns the [type] field) with the encasement
 * registry's own lookup.
 *
 * [type] is a normal declared field, so
 * [net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity.encasements]'s nested-map factory
 * can read it off a saved entry's own raw sub-tag - before any typed holder exists to read it
 * through - to decide which concrete subclass to reconstruct.
 */
abstract class EncasementHolderState(defaultType: ResourceLocation) : AttachmentHolderState(defaultType) {
	val fromRegistry get() = EncasementTypeRegistry.byId(type)

	/**
	 * Whether this member's own cluster currently forms a valid
	 * [AbstractMultiblockManager.Cluster.valid] structure - a cuboid of any shape for
	 * [net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferEncasementType]
	 * ([net.kernelpanicsoft.tubularstorage.crafting.CraftingCpuManager]'s own rule), a compressor
	 * bank/auxiliary tank for [net.kernelpanicsoft.tubularstorage.power.CompressorEncasementType]/
	 * [net.kernelpanicsoft.tubularstorage.power.PressureTankEncasementType]. Lives here rather than
	 * per-type since [net.kernelpanicsoft.tubularstorage.pipe.block.ConnectingEncasementModelBlock.FORMED]
	 * gates the same edge/corner seam-filler rendering for every encasement kind that uses that
	 * block class - not every [EncasementHolderState] subclass needs whole-cluster validity, so a
	 * type that never sets this simply leaves it at its default `false`.
	 */
	var formed: Boolean by field(Boolean.serializer()) { false }
}
