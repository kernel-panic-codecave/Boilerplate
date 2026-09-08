package net.kernelpanicsoft.boilerplate.compat.mekanism

import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.boilerplate.compat.mekanism.ChemicalStorageKind.exposeStorage
import net.minecraft.core.Direction
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType

/**
 * Exposes whatever [selector] answers with as this type's chemical capability.
 *
 * [ChemicalApi.BLOCK] is what turns the storage back into the
 * [mekanism.api.chemical.IChemicalHandler] Mekanism's capability system expects - the direction
 * [MappedBlockLookup] needs a [ChemicalHandler] for, and the reason registering a provider is
 * possible here at all rather than only reading one.
 *
 * The whole of what this loader-registered kind needs in order to be visible from outside, and
 * reached only through [ChemicalStorageKind.exposeStorage]: [net.kernelpanicsoft.boilerplate.registry.TileRegistry]
 * registers every kind at once through
 * [net.kernelpanicsoft.boilerplate.network.exposeResourceStorage], so chemicals are exposed on the
 * Multipart and the warehouse controller by exactly the same code items and fluids are. This file
 * used to carry an object that registered those two by hand as well; it registered the same two
 * providers a second time, and only existed because the generic path did not reach a kind a loader
 * had registered.
 */
@Suppress("UNCHECKED_CAST")
fun <T : BlockEntity> BlockEntityType<T>.exposeChemicalStorage(selector: (T, Direction?) -> CommonStorage<ChemicalResource>?) {
	ChemicalApi.BLOCK.onRegister { registrar ->
		registrar.registerBlockEntities({ entity, direction -> selector(entity as T, direction) }, this)
	}
}
