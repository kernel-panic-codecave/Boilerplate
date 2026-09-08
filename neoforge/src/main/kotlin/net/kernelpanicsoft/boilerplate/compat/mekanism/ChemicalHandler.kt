package net.kernelpanicsoft.boilerplate.compat.mekanism

import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import mekanism.api.Action
import mekanism.api.chemical.ChemicalStack
import mekanism.api.chemical.IChemicalHandler

/**
 * A Common Storage Lib [CommonStorage] seen as a Mekanism [IChemicalHandler] - the exact inverse of
 * [ChemicalStorage], and the direction [MappedBlockLookup] needs in order to *register* a chemical
 * provider rather than only read one.
 *
 * Reading Mekanism's capability only ever needs handler-to-storage; a
 * [earth.terrarium.common_storage_lib.lookup.BlockLookup.onRegister] call hands back storages that
 * Mekanism's own capability system must be given as handlers, so the mapping has to exist both
 * ways for the lookup to be a whole one rather than a read-only half.
 *
 * Mekanism phrases insertion as "here is a stack, keep what you can, hand back the rest", where
 * [CommonStorage] answers "how much moved" - hence the remainder arithmetic, the mirror image of
 * [ChemicalStorage]'s own subtractions. The whole-handler [insertChemical]/[extractChemical]
 * overloads are forwarded rather than left to Mekanism's tank-by-tank defaults, since a storage
 * already knows how it wants a bulk transfer distributed across its own slots.
 */
class ChemicalHandler private constructor(private val storage: CommonStorage<ChemicalResource>) : IChemicalHandler {

	override fun getChemicalTanks(): Int = storage.size()

	override fun getChemicalInTank(tank: Int): ChemicalStack = storage.getResource(tank).toStack(storage.getAmount(tank))

	/**
	 * [CommonStorage] has no "overwrite this slot" of its own, only insert and extract - and the
	 * [IChemicalHandler] contract expressly allows refusing this ("may throw an error if it is
	 * called unexpectedly"). Refusing loudly beats silently dropping whatever was being set.
	 */
	override fun setChemicalInTank(tank: Int, stack: ChemicalStack): Unit =
		throw UnsupportedOperationException("A CommonStorage-backed chemical handler cannot have its tanks overwritten")

	override fun getChemicalTankCapacity(tank: Int): Long = storage.getLimit(tank, storage.getResource(tank))

	override fun isValid(tank: Int, stack: ChemicalStack): Boolean = storage.isResourceValid(tank, ChemicalResource.of(stack))

	override fun insertChemical(tank: Int, stack: ChemicalStack, action: Action): ChemicalStack =
		remainderOf(stack, storage.insert(tank, ChemicalResource.of(stack), stack.amount, action.simulate()))

	override fun insertChemical(stack: ChemicalStack, action: Action): ChemicalStack =
		remainderOf(stack, storage.insert(ChemicalResource.of(stack), stack.amount, action.simulate()))

	override fun extractChemical(tank: Int, amount: Long, action: Action): ChemicalStack {
		// Extraction here names an amount but no chemical, so whatever the tank already holds is
		// what comes out - the storage side has no "give me anything" extract of its own.
		val held = storage.getResource(tank)
		if (held.isBlank || amount <= 0L) return ChemicalStack.EMPTY
		return held.toStack(storage.extract(tank, held, amount, action.simulate()))
	}

	override fun extractChemical(stack: ChemicalStack, action: Action): ChemicalStack {
		if (stack.isEmpty) return ChemicalStack.EMPTY
		val resource = ChemicalResource.of(stack)
		return resource.toStack(storage.extract(resource, stack.amount, action.simulate()))
	}

	private fun remainderOf(offered: ChemicalStack, moved: Long): ChemicalStack =
		if (moved >= offered.amount) ChemicalStack.EMPTY else offered.copyWithAmount(offered.amount - moved)

	companion object {
		/**
		 * [storage] as a handler - unwrapping rather than re-wrapping when it is itself a
		 * [ChemicalStorage], so a round trip through [MappedBlockLookup] hands back the machine's
		 * own real handler instead of a two-layer adapter over it (which would, among other things,
		 * have lost [setChemicalInTank] for no reason).
		 */
		fun of(storage: CommonStorage<ChemicalResource>): IChemicalHandler =
			(storage as? ChemicalStorage)?.handler ?: ChemicalHandler(storage)
	}
}
