package net.kernelpanicsoft.boilerplate.warehouse.rack

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.boilerplate.network.ItemResourceSerializer
import net.kernelpanicsoft.boilerplate.network.SItemResource
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.state.BlockState
import kotlinx.serialization.builtins.serializer

/**
 * Unstackable-item rack: purpose-built for the NBT-heavy, `maxStackSize == 1` case (enchanted
 * tools/bows, written books, mob-farm drops - see `docs/design/m3-warehouse-storage.md`'s "Rack
 * types" section) - a [SLOTS]-wide [UncappedItemStorage] dedupes by full resource identity (item
 * id + data components), since its underlying `TransferUtil` insert already fills an existing
 * matching slot before an empty one - two drops with identical components collapse into one
 * record with a count rather than one entry each, and only genuinely distinct component sets
 * consume a new slot. [CAPACITY_PER_RECORD] is large rather than uncapped outright, just so a
 * single record can't overflow [Long] math.
 */
class UnstackableRackBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.UnstackableRack, pos, state), RackBlockEntity {

	private val resources: MutableList<SItemResource> by listField(ItemResourceSerializer) { List(SLOTS) { ItemResource.BLANK } }
	private val amounts: MutableList<Long> by listField(Long.serializer()) { List(SLOTS) { 0L } }

	val storage: CommonStorage<ItemResource> = UncappedItemStorage(CAPACITY_PER_RECORD, resources, amounts) { setChanged() }

	override fun describeContents(): Component {
		val used = resources.count { !it.isBlank }
		return Component.literal("Unstackable Rack: $used/$SLOTS records")
	}

	companion object {
		const val SLOTS = 27
		const val CAPACITY_PER_RECORD = Long.MAX_VALUE / 2
	}
}
