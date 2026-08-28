package net.kernelpanicsoft.tubularstorage.pipe.hook

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.pipe.attachment.ItemStorageExposer
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity

/**
 * Self-contained state for one [InterfaceHookType] attachment: a small physical buffer
 * ([stock]) a player/hopper/pipe interacts with directly, the same way a real inventory would
 * ("normal interaction is like the ME interface" - `docs/design/m2-sorting-routing.md`'s subnet
 * boundary section). A non-blank slot's own target is its ordinary
 * [net.kernelpanicsoft.archie.transfer.ArchieItemSlot.getLimit] (a full stack of whatever
 * occupies it) - no separate configured-amount field, matching [RequesterHookState.request]'s own
 * "put a stack of what you want" simplicity, just physically held here instead of read off an
 * adjacent inventory. Only [ItemStorageExposer], not [net.kernelpanicsoft.tubularstorage.pipe.attachment.FallbackItemStorageExposer] -
 * see that interface's own KDoc for why.
 */
class InterfaceHookState : HookHolderState(InterfaceHookType.ID), ItemStorageExposer {
	val stock: ArchieItemStorage by itemField(SLOTS)

	override fun exposedItemStorage(tile: MultipartBlockEntity): CommonStorage<ItemResource> = stock

	/** Ticks since this hook last attempted to push [stock] into the network; resets to 0 on every attempt, successful or not - see [InterfaceHookType.tick]. */
	var ticksSincePush: Int = 0

	companion object {
		const val SLOTS = 9
	}
}
