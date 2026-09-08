package net.kernelpanicsoft.boilerplate.compat.mekanism

import mekanism.api.MekanismAPI
import mekanism.api.chemical.Chemical
import net.minecraft.resources.ResourceLocation

/** Chemical lookups by registry name - what persistence and the filter conditions resolve through. */
object MekanismChemicalRegistry {
	/** [name]'s own [Chemical], or `null` when this game has no such chemical (a pack change, a removed addon). */
	fun byName(name: String): Chemical? {
		val id = ResourceLocation.tryParse(name) ?: return null
		return MekanismAPI.CHEMICAL_REGISTRY.get(id).takeIf { !it.isEmptyType }
	}
}
