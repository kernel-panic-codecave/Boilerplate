package net.kernelpanicsoft.boilerplate.compat.mekanism

import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import earth.terrarium.common_storage_lib.storage.base.StorageSlot
import mekanism.api.Action
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import mekanism.api.chemical.IChemicalHandler
import net.minecraft.world.item.ItemStack

/**
 * A Mekanism [IChemicalHandler] seen as a Common Storage Lib [CommonStorage].
 *
 * One tank per slot, in the handler's own order, so a machine's separate input and output tanks stay
 * separate here too rather than being flattened into one pool. Every call is forwarded live - this
 * holds no state of its own and never caches, since the handler behind it is the machine's real one
 * and can change under it at any tick.
 *
 * Mekanism phrases transfers as "here is a stack, tell me what is left over", where this side is
 * asked "how much moved" - hence the subtractions rather than direct returns.
 */
class ChemicalStorage(internal val handler: IChemicalHandler) : CommonStorage<ChemicalResource> {

	override fun size(): Int = handler.chemicalTanks

	override fun get(index: Int): StorageSlot<ChemicalResource> = Tank(index)

	override fun insert(resource: ChemicalResource, amount: Long, simulate: Boolean): Long {
		if (resource.isBlank || amount <= 0L) return 0L
		val offered = resource.toStack(amount)
		val leftover = handler.insertChemical(offered, action(simulate))
		return amount - leftover.amount
	}

	override fun extract(resource: ChemicalResource, amount: Long, simulate: Boolean): Long {
		if (resource.isBlank || amount <= 0L) return 0L
		return handler.extractChemical(resource.toStack(amount), action(simulate)).amount
	}

	private inner class Tank(private val index: Int) : StorageSlot<ChemicalResource> {
		override fun getResource(): ChemicalResource = ChemicalResource.of(handler.getChemicalInTank(index))

		override fun getAmount(): Long = handler.getChemicalInTank(index).amount

		override fun getLimit(resource: ChemicalResource): Long = handler.getChemicalTankCapacity(index)

		override fun isResourceValid(resource: ChemicalResource): Boolean =
			resource.isBlank || handler.isValid(index, resource.toStack(1L))

		override fun insert(resource: ChemicalResource, amount: Long, simulate: Boolean): Long {
			if (resource.isBlank || amount <= 0L) return 0L
			val offered = resource.toStack(amount)
			return amount - handler.insertChemical(index, offered, action(simulate)).amount
		}

		override fun extract(resource: ChemicalResource, amount: Long, simulate: Boolean): Long {
			if (resource.isBlank || amount <= 0L) return 0L
			// Extraction is by amount, not by stack, so the tank's own contents decide what comes
			// out - a request for something the tank does not hold must take nothing rather than
			// whatever happens to be in there.
			if (getResource() != resource) return 0L
			return handler.extractChemical(index, amount, action(simulate)).amount
		}
	}

	private companion object {
		fun action(simulate: Boolean): Action = if (simulate) Action.SIMULATE else Action.EXECUTE
	}
}

/**
 * A chemical container held *inside an item slot*, writing the modified stack back into the slot it
 * came from after every real transfer.
 *
 * Mekanism keeps a container's contents in its [ItemStack]'s own data components, and its item
 * capability mutates the stack it was handed. Nothing here has a live stack to hand it: an
 * [net.kernelpanicsoft.archie.transfer.ArchieItemStorage] slot stores a resource and an amount and
 * builds a fresh [ItemStack] on every read. So the capability faithfully filled a throwaway copy
 * and the chemical went nowhere - drained out of the terminal's inbox and into an object that was
 * discarded on the next line.
 *
 * The fluid kind never hit this because Common Storage Lib's own item lookup takes an
 * [earth.terrarium.common_storage_lib.context.ItemContext] that does the write-back for it;
 * NeoForge's plain `ItemCapability` has no such notion, so this supplies it.
 */
class ItemChemicalStorage(
	private val delegate: CommonStorage<ChemicalResource>,
	private val stack: ItemStack,
	private val holder: CommonStorage<ItemResource>,
	private val slot: Int,
) : CommonStorage<ChemicalResource> {

	override fun size(): Int = delegate.size()

	override fun get(index: Int): StorageSlot<ChemicalResource> = WriteBackSlot(delegate[index])

	override fun insert(resource: ChemicalResource, amount: Long, simulate: Boolean): Long =
		delegate.insert(resource, amount, simulate).also { if (it > 0L && !simulate) writeBack() }

	override fun extract(resource: ChemicalResource, amount: Long, simulate: Boolean): Long =
		delegate.extract(resource, amount, simulate).also { if (it > 0L && !simulate) writeBack() }

	/** Replaces [holder]'s own [slot] with the stack Mekanism has just rewritten. */
	private fun writeBack() {
		val held = holder.getResource(slot)
		val amount = holder.getAmount(slot)
		if (amount > 0L) holder.extract(slot, held, amount, false)
		holder.insert(slot, ItemResource.of(stack), 1L, false)
	}

	private inner class WriteBackSlot(private val delegate: StorageSlot<ChemicalResource>) : StorageSlot<ChemicalResource> by delegate {
		override fun insert(resource: ChemicalResource, amount: Long, simulate: Boolean): Long =
			delegate.insert(resource, amount, simulate).also { if (it > 0L && !simulate) writeBack() }

		override fun extract(resource: ChemicalResource, amount: Long, simulate: Boolean): Long =
			delegate.extract(resource, amount, simulate).also { if (it > 0L && !simulate) writeBack() }
	}
}
