package net.kernelpanicsoft.boilerplate.compat.mekanism

import earth.terrarium.common_storage_lib.lookup.BlockLookup
import earth.terrarium.common_storage_lib.lookup.ItemLookup
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.minecraft.core.Direction

/**
 * Mekanism's chemical capability behind Common Storage Lib's own lookups, so the rest of this mod
 * can ask for a chemical storage exactly as it asks for an item or fluid one.
 *
 * Shaped as CSL's own API objects are - [earth.terrarium.common_storage_lib.item.ItemApi]'s
 * `BLOCK`/`ITEM`, and [net.kernelpanicsoft.boilerplate.power.PressureApi]'s `BLOCK` - a bare lookup
 * constant per place a chemical can live, rather than this object being a lookup itself.
 * [net.kernelpanicsoft.boilerplate.pipe.network.ResourceNetworkType] is written against
 * [BlockLookup] and nothing else, so [BLOCK] is the whole of what the transport layer needs to
 * learn about chemicals.
 *
 * The translation itself is [MappedBlockLookup]'s: [ChemicalStorage] out of Mekanism's own
 * [mekanism.api.chemical.IChemicalHandler], [ChemicalHandler] back into it.
 */
object ChemicalApi {

	/** A chemical storage on a block, from whichever face the query names - Mekanism's own sided handler capability. */
	@JvmField
	val BLOCK: BlockLookup<CommonStorage<ChemicalResource>, Direction?> =
		MappedBlockLookup(MekanismChemicals.BLOCK, ::ChemicalStorage, ChemicalHandler::of)

	/** A chemical storage inside an item - a Mekanism chemical tank, or anything else exposing the same capability. */
	@JvmField
	val ITEM: ItemLookup<CommonStorage<ChemicalResource>, Void?> =
		MappedItemLookup(MekanismChemicals.ITEM, ::ChemicalStorage, ChemicalHandler::of)
}
