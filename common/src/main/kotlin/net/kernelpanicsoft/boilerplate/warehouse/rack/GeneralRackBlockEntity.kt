package net.kernelpanicsoft.boilerplate.warehouse.rack

import dev.architectury.registry.menu.ExtendedMenuProvider
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.archie.serialization.field
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
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
	NBTBlockEntity(TileRegistry.GeneralRack, pos, state), RackBlockEntity, ExtendedMenuProvider {
	override val intrinsicPriority: Int = 0
	override var priority: Int get() = routing.priority
		set(value)
		{
			routing = routing.copy(priority = value)
		}
	@Sync
	override var routing: RoutingModule by field { RoutingModule(priority = intrinsicPriority) }
	override val filter: ArchieItemStorage by itemField(1, filter = { it.item is FilterCardItem })
	val storage: ArchieItemStorage by itemField(SLOTS, filter = { acceptsByFilter(it) })

	override fun describeContents(): Component {
		val used = (0 until storage.size()).count { !storage.get(it).resource.isBlank }
		return Component.literal("General Rack: $used/$SLOTS slots used")
	}

	override fun saveExtraData(buf: FriendlyByteBuf)
	{
		buf.writeBlockPos(blockPos)
	}

	override fun getDisplayName(): Component = describeContents()

	override fun createMenu(
		i: Int,
		inventory: Inventory,
		player: Player
	): AbstractContainerMenu = GeneralRackMenu(i, inventory, this)

	companion object {
		const val SLOTS = 54
	}
}
