package net.kernelpanicsoft.boilerplate.warehouse.rack

import dev.architectury.registry.menu.ExtendedMenuProvider
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.archie.serialization.field
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.resource.ItemResourceSerializer
import net.kernelpanicsoft.boilerplate.resource.SItemResource
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
 * Unstackable-item rack: purpose-built for the NBT-heavy, `maxStackSize == 1` case (enchanted
 * tools/bows, written books, mob-farm drops - see `docs/design/m3-warehouse-storage.md`'s "Rack
 * types" section) - a [RECORDS]-wide [UncappedItemStorage] dedupes by full resource identity (item
 * id + data components), since its underlying `TransferUtil` insert already fills an existing
 * matching slot before an empty one - two drops with identical components collapse into one
 * record with a count rather than one entry each, and only genuinely distinct component sets
 * consume a new slot. [CAPACITY] is large rather than uncapped outright, just so a
 * single record can't overflow [Long] math.
 */
class UnstackableRackBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.UnstackableRack, pos, state), RackBlockEntity, ExtendedMenuProvider {
	override val intrinsicPriority: Int = 2
	override var priority: Int get() = routing.priority
		set(value)
		{
			routing = routing.copy(priority = value)
		}
	@Sync
	override var routing: RoutingModule by field { RoutingModule(priority = intrinsicPriority) }
	override val filter: ArchieItemStorage by itemField(1, filter = { it.item is FilterCardItem })
	private val resources: MutableList<SItemResource> by listField(ItemResourceSerializer) {mutableListOf()}
	private val amounts: MutableList<Long> by listField(Long.serializer()) {mutableListOf()}

	val storage = UncappedItemStorage(
		maxRecords = RECORDS,
		totalCapacity = CAPACITY,
		resources = { resources },
		amounts = { amounts },
		filter = { acceptsByFilter(it) && it.item.defaultMaxStackSize == 1 }
	) { setChanged() }

	override fun describeContents(): Component {
		val used = storage.used()
		val records = storage.recordCount()
		return Component.literal("Unstackable Rack: $used/$CAPACITY items, $records records")
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
	): AbstractContainerMenu = UnstackableRackMenu(i, inventory, this)

	companion object {
		const val RECORDS = Int.MAX_VALUE / 4
		const val CAPACITY = 10_000L
	}
}
