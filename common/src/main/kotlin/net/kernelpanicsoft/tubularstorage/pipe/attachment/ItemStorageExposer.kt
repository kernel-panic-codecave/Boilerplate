package net.kernelpanicsoft.tubularstorage.pipe.attachment

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity

/**
 * A [net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState]/
 * [net.kernelpanicsoft.tubularstorage.pipe.encasement.EncasementHolderState] that exposes an
 * item-storage surface through [net.kernelpanicsoft.tubularstorage.warehouse.rack.exposeCommonItemStorage]'s
 * generic lookup on [net.kernelpanicsoft.tubularstorage.registry.TileRegistry.Multipart] - a new
 * exposing type just implements this, rather than needing its own hardcoded `as?` branch added
 * there. [tile] is passed through rather than captured, since what a state actually exposes can
 * depend on the segment it's wrapping (a Crafting CPU member's own cluster lookup, say).
 */
interface ItemStorageExposer {
	fun exposedItemStorage(tile: MultipartBlockEntity): CommonStorage<ItemResource>?
}

/**
 * An [ItemStorageExposer] hook additionally eligible for a direction-less query's own any-face
 * fallback search (see [net.kernelpanicsoft.tubularstorage.registry.TileRegistry.Multipart]'s own
 * KDoc) - [net.kernelpanicsoft.tubularstorage.pipe.hook.InterfaceHookState] deliberately implements
 * only the plain [ItemStorageExposer], not this: guessing wrong there means silently crossing a
 * subnet boundary meant to stay isolated.
 */
interface FallbackItemStorageExposer : ItemStorageExposer
