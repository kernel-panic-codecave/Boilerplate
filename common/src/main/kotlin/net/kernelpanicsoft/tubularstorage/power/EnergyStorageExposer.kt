package net.kernelpanicsoft.tubularstorage.power

import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity

/**
 * An encasement state that exposes a real energy endpoint through
 * [net.kernelpanicsoft.tubularstorage.registry.TileRegistry.Multipart]'s own
 * [net.kernelpanicsoft.archie.transfer.exposeEnergyStorage] selector - a new tank/compressor-like type just implements this, rather
 * than needing its own hardcoded `as?` branch added there. Mirrors
 * [net.kernelpanicsoft.tubularstorage.pipe.attachment.ItemStorageExposer]'s own generalization of
 * the equivalent item-storage lookup.
 */
interface EnergyStorageExposer {
	fun exposedEnergyStorage(tile: MultipartBlockEntity): ArchieEnergyStorage?
}

interface FallbackEnergyStorageExposer : EnergyStorageExposer
