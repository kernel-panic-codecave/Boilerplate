package net.kernelpanicsoft.boilerplate.registry

import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementState
import net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType
import net.kernelpanicsoft.boilerplate.crafting.CraftingTankEncasementState
import net.kernelpanicsoft.boilerplate.crafting.CraftingTankEncasementType
import net.kernelpanicsoft.boilerplate.pipe.encasement.EncasementHolderState
import net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType
import net.kernelpanicsoft.boilerplate.power.CompressorEncasementState
import net.kernelpanicsoft.boilerplate.power.CompressorEncasementType
import net.kernelpanicsoft.boilerplate.power.PressureTankEncasementState
import net.kernelpanicsoft.boilerplate.power.PressureTankEncasementType
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation

/** Registers Boilerplate's [PipeEncasementType]s into the custom registry [Registrars] declares. */
@Suppress("UNCHECKED_CAST")
object EncasementTypeRegistry : ADeferredRegistryHolder<PipeEncasementType<out EncasementHolderState>>(
	Boilerplate.MOD,
	Registrars.ENCASEMENT_TYPE.key() as ResourceKey<Registry<PipeEncasementType<out EncasementHolderState>>>,
) {
	val CraftingBuffer: PipeEncasementType<CraftingBufferEncasementState> by register(CraftingBufferEncasementType.ID) { CraftingBufferEncasementType }
	val CraftingTank: PipeEncasementType<CraftingTankEncasementState> by register(CraftingTankEncasementType.ID) { CraftingTankEncasementType }
	val Compressor: PipeEncasementType<CompressorEncasementState> by register(CompressorEncasementType.ID) { CompressorEncasementType }
	val PressureTank: PipeEncasementType<PressureTankEncasementState> by register(PressureTankEncasementType.ID) { PressureTankEncasementType }

	/** Looks up a registered [PipeEncasementType] by its full id - see [HookTypeRegistry.byId], which this mirrors exactly, for why this goes through the live [Registrar][net.kernelpanicsoft.archie.registries.RegistrarHelper] rather than this holder's own bookkeeping [Map]. */
	fun byId(id: ResourceLocation): PipeEncasementType<EncasementHolderState>? = Registrars.ENCASEMENT_TYPE.get(id) as? PipeEncasementType<EncasementHolderState>
}
