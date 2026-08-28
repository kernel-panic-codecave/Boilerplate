package net.kernelpanicsoft.boilerplate.warehouse.rack

import dev.architectury.registry.registries.RegistrySupplier
import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.minecraft.core.Direction
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType

/**
 * [net.kernelpanicsoft.archie.transfer.exposeItemStorage]'s equivalent for a rack whose storage
 * isn't an [net.kernelpanicsoft.archie.transfer.ArchieItemStorage] - that helper's own signature
 * is fixed to that one concrete type, so [BulkRackBlockEntity]/[UnstackableRackBlockEntity] (both
 * backed by [UncappedItemStorage]) register against [ItemApi.BLOCK] directly instead, the same
 * way Archie's own helper does internally. [GeneralRackBlockEntity] doesn't need this - a plain
 * [net.kernelpanicsoft.archie.transfer.ArchieItemStorage] field uses Archie's helper as-is.
 */
fun <T : BlockEntity> BlockEntityType<T>.exposeCommonItemStorage(selector: (T, Direction?) -> CommonStorage<ItemResource>?) {
	ItemApi.BLOCK.onRegister { registrar ->
		registrar.registerBlockEntities(
			{ blockEntity, direction ->
				@Suppress("UNCHECKED_CAST")
				selector(blockEntity as T, direction)
			},
			this,
		)
	}
}

/** [exposeCommonItemStorage] overload for a selector that doesn't need the query direction. */
fun <T : BlockEntity> BlockEntityType<T>.exposeCommonItemStorage(selector: (T) -> CommonStorage<ItemResource>?) =
	exposeCommonItemStorage { be, _ -> selector(be) }

/** See [net.kernelpanicsoft.archie.transfer.exposeItemStorage]'s identical `RegistrySupplier` overloads for why this is chained on the raw supplier. */
fun <T : BlockEntity> RegistrySupplier<BlockEntityType<T>>.exposeCommonItemStorage(selector: (T, Direction?) -> CommonStorage<ItemResource>?) =
	listen { it.exposeCommonItemStorage(selector) }

/** [exposeCommonItemStorage] overload for a selector that doesn't need the query direction. */
fun <T : BlockEntity> RegistrySupplier<BlockEntityType<T>>.exposeCommonItemStorage(selector: (T) -> CommonStorage<ItemResource>?) =
	listen { it.exposeCommonItemStorage(selector) }
