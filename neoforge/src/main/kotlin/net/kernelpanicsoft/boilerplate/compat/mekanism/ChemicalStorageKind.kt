package net.kernelpanicsoft.boilerplate.compat.mekanism

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.boilerplate.resource.ResourceStorageKind
import net.kernelpanicsoft.boilerplate.resource.roomFor
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType

/**
 * How the chemical kind's storage is reached and moved through - the chemical twin of
 * [net.kernelpanicsoft.boilerplate.resource.FluidStorageKind].
 *
 * Registering this (through [ChemicalResourceKindRegistry]) is what makes chemicals warehouse-able, pipeable,
 * craftable and terminal-listable in one go: nothing in those systems asks what a chemical is, only
 * what its kind can do.
 *
 * Every cast here is guarded by this kind having produced or found the storage in the first place,
 * which is the same contract [ResourceStorageKind] documents for the kinds Boilerplate ships.
 */
@Suppress("UNCHECKED_CAST")
object ChemicalStorageKind : ResourceStorageKind {

	override fun find(level: ServerLevel, pos: BlockPos, direction: Direction?): CommonStorage<*>? =
		ChemicalApi.BLOCK.find(level, pos, direction)

	override fun <T : BlockEntity> BlockEntityType<T>.exposeStorage(
		selector: (tile: T, direction: Direction?) -> CommonStorage<*>?
	)
	{
		exposeChemicalStorage { tile, direction -> selector(tile, direction)?.cast() }
	}

	/**
	 * A Mekanism chemical tank item, or anything else exposing the same capability - what a click on
	 * a terminal inbox column fills and empties.
	 *
	 * Wrapped in an [ItemChemicalStorage] rather than handed over bare: Mekanism writes a
	 * container's contents into the item's own data components, and there is no live [ItemStack]
	 * here to write into - see that class for what filling one without this did.
	 *
	 * **One item at a time.** A whole stack shares one set of data components, so filling a stack of
	 * three tanks from a single column would hand back three full tanks out of one column's worth of
	 * chemical. There is nowhere to put the other two unfilled ones, so a slot holding more than one
	 * is refused outright rather than duplicating.
	 */
	override fun findInItem(holder: CommonStorage<ItemResource>, slot: Int): CommonStorage<*>? {
		val resource = holder.getResource(slot)
		if (resource.isBlank || holder.getAmount(slot) != 1L) return null
		val stack = resource.toStack(1)
		val handler = ChemicalApi.ITEM.find(stack, null) ?: return null
		return ItemChemicalStorage(handler, stack, holder, slot)
	}

	override fun insert(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val chemical = resource as? ChemicalResource ?: return 0
		return (storage as CommonStorage<ChemicalResource>).insert(chemical, amount, simulate)
	}

	override fun extract(storage: CommonStorage<*>, resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val chemical = resource as? ChemicalResource ?: return 0
		return (storage as CommonStorage<ChemicalResource>).extract(chemical, amount, simulate)
	}

	override fun roomFor(storage: CommonStorage<*>, resource: ResourceComponent, limit: Long): Long {
		val chemical = resource as? ChemicalResource ?: return 0
		return (storage as CommonStorage<ChemicalResource>).roomFor(chemical, limit)
	}

	override fun insertInto(storage: CommonStorage<*>, index: Int, resource: ResourceComponent, amount: Long, simulate: Boolean): Long {
		val chemical = resource as? ChemicalResource ?: return 0
		return (storage as CommonStorage<ChemicalResource>).insert(index, chemical, amount, simulate)
	}

	/** [capacity] is already in millibuckets, which is what Mekanism counts chemicals in - no platform conversion is needed the way a fluid's is. */
	override fun createBuffer(slots: Int, capacity: Long): CommonStorage<*> = ChemicalBuffer(slots, capacity)
}
