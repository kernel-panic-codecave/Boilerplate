package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.state.BlockState

/**
 * General-purpose rack: ordinary multi-item shelving, closest to a plain chest/barrel's own
 * semantics (see `docs/design/m3-warehouse-storage.md`'s "Rack types" section) - just an
 * [ArchieItemStorage] with [SLOTS] slots, exposed to [earth.terrarium.common_storage_lib.item.ItemApi.BLOCK]
 * the ordinary way via [net.kernelpanicsoft.archie.transfer.exposeItemStorage] (see
 * [TileRegistry.GeneralRack]), unlike [BulkRackBlockEntity]/[UnstackableRackBlockEntity]'s custom
 * [UncappedItemStorage].
 */
class GeneralRackBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.GeneralRack, pos, state), RackBlockEntity {

	val storage: ArchieItemStorage by itemField(SLOTS)

	override fun describeContents(): Component {
		val used = (0 until storage.size()).count { !storage.get(it).resource.isBlank }
		return Component.literal("General Rack: $used/$SLOTS slots used")
	}

	companion object {
		const val SLOTS = 54
	}
}
