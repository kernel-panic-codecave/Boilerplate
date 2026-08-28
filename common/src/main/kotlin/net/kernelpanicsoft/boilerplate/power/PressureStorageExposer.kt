package net.kernelpanicsoft.boilerplate.power

import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity

/**
 * An encasement state that exposes a real pressure endpoint through
 * [net.kernelpanicsoft.boilerplate.registry.TileRegistry.Multipart]'s own
 * [exposePressureStorage] selector - a new tank/compressor-like type just implements this, rather
 * than needing its own hardcoded `as?` branch added there. Mirrors
 * [net.kernelpanicsoft.boilerplate.pipe.attachment.ItemStorageExposer]'s own generalization of
 * the equivalent item-storage lookup.
 */
interface PressureStorageExposer {
	fun exposedPressureStorage(tile: MultipartBlockEntity): ArchieEnergyStorage?
}

interface FallbackPressureStorageExposer : PressureStorageExposer
