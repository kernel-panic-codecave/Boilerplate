package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferEncasementState
import net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferEncasementType
import net.kernelpanicsoft.tubularstorage.pipe.encasement.EncasementHolderState
import net.kernelpanicsoft.tubularstorage.pipe.encasement.PipeEncasementType
import net.kernelpanicsoft.tubularstorage.power.CompressorEncasementState
import net.kernelpanicsoft.tubularstorage.power.CompressorEncasementType
import net.kernelpanicsoft.tubularstorage.power.PressureTankEncasementState
import net.kernelpanicsoft.tubularstorage.power.PressureTankEncasementType
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation

/** Registers Tubular Storage's [PipeEncasementType]s into the custom registry [Registrars] declares. */
@Suppress("UNCHECKED_CAST")
object EncasementTypeRegistry : ADeferredRegistryHolder<PipeEncasementType<out EncasementHolderState>>(
	TubularStorage.MOD,
	Registrars.ENCASEMENT_TYPE.key() as ResourceKey<Registry<PipeEncasementType<out EncasementHolderState>>>,
) {
	val CraftingBuffer: PipeEncasementType<CraftingBufferEncasementState> by register(CraftingBufferEncasementType.ID) { CraftingBufferEncasementType }
	val Compressor: PipeEncasementType<CompressorEncasementState> by register(CompressorEncasementType.ID) { CompressorEncasementType }
	val PressureTank: PipeEncasementType<PressureTankEncasementState> by register(PressureTankEncasementType.ID) { PressureTankEncasementType }

	/** Looks up a registered [PipeEncasementType] by its full id - see [HookTypeRegistry.byId], which this mirrors exactly, for why this goes through the live [Registrar][net.kernelpanicsoft.archie.registries.RegistrarHelper] rather than this holder's own bookkeeping [Map]. */
	fun byId(id: ResourceLocation): PipeEncasementType<EncasementHolderState>? = Registrars.ENCASEMENT_TYPE.get(id) as? PipeEncasementType<EncasementHolderState>
}
