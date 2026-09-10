package net.kernelpanicsoft.boilerplate.warehouse.rack

import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import dev.architectury.registry.menu.ExtendedMenuProvider
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
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
 * Bulk/deep-storage rack: a large quantity of a single resource, minimal per-slot overhead (see
 * `docs/design/m3-warehouse-storage.md`'s "Rack types" section) - a size-1
 * [UncappedItemStorage] holding up to [CAPACITY] of whatever it's first given, far past
 * [net.kernelpanicsoft.archie.transfer.ArchieItemSlot]'s vanilla `maxStackSize` cap.
 */
class BulkRackBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.BulkRack, pos, state), RackBlockEntity, ExtendedMenuProvider {
	override val intrinsicPriority: Int = 1
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

	// Providers, not the lists themselves - these initializers run before loadAdditional has put
	// anything in NBT, so a captured list is pinned to the empty defaults forever. See
	// UncappedItemStorage's own KDoc.
	val storage: CommonStorage<ItemResource> = UncappedItemStorage(
		maxRecords = RECORDS,
		totalCapacity = BoilerplateConfig.Gameplay.Capacities.bulkRackCapacity,
		resources = { resources },
		amounts = { amounts },
		filter = { acceptsByFilter(it) }
	) { setChanged() }

	override fun describeContents(): Component {
		// getOrNull, not [0] - records are allocated on demand now, so an empty rack's own backing
		// lists are genuinely empty rather than holding one blank placeholder.
		val resource = resources.getOrNull(0)
		if (resource == null || resource.isBlank) return Component.literal("Bulk Rack: Empty")
		return Component.literal("Bulk Rack: ${amounts.getOrNull(0) ?: 0L}x ").append(resource.cachedStack.hoverName)
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
	): AbstractContainerMenu = BulkRackMenu(i, inventory, this)

	companion object {
		/** One resource per rack - see this class's own KDoc. */
		const val RECORDS = 1
		const val CAPACITY = 1_000_000L
	}
}
