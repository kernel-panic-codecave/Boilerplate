package net.kernelpanicsoft.boilerplate.compat.mekanism

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import mekanism.api.chemical.Chemical
import mekanism.api.chemical.ChemicalStack
import net.minecraft.core.component.DataComponentMap

/**
 * One Mekanism [Chemical] as a Common Storage Lib resource, so everything in this mod that moves a
 * "resource" can move a chemical without knowing what one is.
 *
 * The adapter the whole chemical kind rests on. Boilerplate's pipes, warehouse, crafting and
 * terminal are all written against [ResourceComponent]; Mekanism's chemicals are their own type
 * hierarchy with their own handler. This is the single place the two meet.
 *
 * A chemical carries no per-stack data of its own in Mekanism 10.7 - attributes live on the
 * [Chemical] itself, which is a registry singleton - so the component map is always empty and two
 * resources are equal exactly when they name the same chemical.
 */
class ChemicalResource private constructor(val chemical: Chemical) : ResourceComponent(DataComponentMap.EMPTY) {

	override fun isBlank(): Boolean = chemical.isEmptyType

	/** [amount] of this chemical as Mekanism's own stack type - what every handler call is phrased in. */
	fun toStack(amount: Long): ChemicalStack = if (isBlank) ChemicalStack.EMPTY else ChemicalStack(chemical, amount)

	/**
	 * Value equality on the chemical alone.
	 *
	 * [ResourceComponent] does not define equality, and a resource without it silently breaks every
	 * map and cache keyed on one - the same trap `FluidResource` walks into and that
	 * [net.kernelpanicsoft.boilerplate.network.ResourceIdentity] exists to work around. A chemical
	 * is a registry singleton, so identity would be enough; this is spelled out anyway so the
	 * contract does not depend on that staying true.
	 */
	override fun equals(other: Any?): Boolean =
		this === other || (other is ChemicalResource && chemical === other.chemical)

	override fun hashCode(): Int = System.identityHashCode(chemical)

	override fun toString(): String = "ChemicalResource(${chemical.registryName})"

	companion object {
		/** The empty chemical - this kind's answer to "nothing here", and what an empty tank reads as. */
		val BLANK: ChemicalResource = ChemicalResource(ChemicalStack.EMPTY.chemical)

		fun of(chemical: Chemical): ChemicalResource = if (chemical.isEmptyType) BLANK else ChemicalResource(chemical)

		fun of(stack: ChemicalStack): ChemicalResource = of(stack.chemical)
	}
}
