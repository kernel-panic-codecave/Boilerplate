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
 * Bulk/deep-storage rack: a large quantity of a single resource, minimal per-slot overhead (see
 * `docs/design/m3-warehouse-storage.md`'s "Rack types" section) - a size-1
 * [UncappedItemStorage] holding up to [CAPACITY] of whatever it's first given, far past
 * [net.kernelpanicsoft.archie.transfer.ArchieItemSlot]'s vanilla `maxStackSize` cap.
 */
class BulkRackBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.BulkRack, pos, state), RackBlockEntity {

	private val resources: MutableList<SItemResource> by listField(ItemResourceSerializer) { listOf(ItemResource.BLANK) }
	private val amounts: MutableList<Long> by listField(Long.serializer()) { listOf(0L) }

	val storage: CommonStorage<ItemResource> = UncappedItemStorage(CAPACITY, resources, amounts) { setChanged() }

	override fun describeContents(): Component {
		val resource = resources[0]
		if (resource.isBlank) return Component.literal("Bulk Rack: Empty")
		return Component.literal("Bulk Rack: ${amounts[0]}x ").append(resource.cachedStack.hoverName)
	}

	companion object {
		const val CAPACITY = 1_000_000L
	}
}
