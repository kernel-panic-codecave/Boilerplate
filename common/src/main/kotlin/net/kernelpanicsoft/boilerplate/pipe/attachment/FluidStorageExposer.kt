package net.kernelpanicsoft.boilerplate.pipe.attachment

import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity

/**
 * A [net.kernelpanicsoft.boilerplate.pipe.hook.HookHolderState]/
 * [net.kernelpanicsoft.boilerplate.pipe.encasement.EncasementHolderState] that exposes a
 * **fluid**-storage surface on the segment it is attached to - the fluid counterpart of
 * [ItemStorageExposer], resolved the same way on
 * [net.kernelpanicsoft.boilerplate.registry.TileRegistry.Multipart], so a new exposing type just
 * implements this rather than needing a hardcoded branch there.
 *
 * [tile] is passed through rather than captured, since what a state exposes can depend on the
 * segment it is wrapping.
 */
interface FluidStorageExposer {
	fun exposedFluidStorage(tile: MultipartBlockEntity): CommonStorage<FluidResource>?
}

/**
 * A [FluidStorageExposer] additionally eligible for a direction-less query's any-face fallback -
 * the fluid counterpart of [FallbackItemStorageExposer].
 *
 * [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState] deliberately implements only the
 * plain [FluidStorageExposer], for the same reason it does on the item side: guessing a face wrong
 * there means silently crossing a subnet boundary that exists to stay isolated.
 */
interface FallbackFluidStorageExposer : FluidStorageExposer
