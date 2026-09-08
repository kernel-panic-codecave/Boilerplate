package net.kernelpanicsoft.boilerplate.compat.mekanism

import mekanism.api.chemical.IChemicalHandler
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.capabilities.BlockCapability
import net.neoforged.neoforge.capabilities.ItemCapability

/**
 * Mekanism's own chemical capability, resolved by name rather than by reference.
 *
 * Only Mekanism's `:api` artifact is on this module's compile classpath, and the capability objects
 * live in its `common` - so `mekanism.common.capabilities.Capabilities.CHEMICAL` cannot be named
 * here. NeoForge's `create*` methods are idempotent per `(name, type, context)`, though: building a
 * capability with the same three parts hands back the very instance Mekanism registered, which is
 * how this reaches it without depending on internals.
 *
 * Both parts are what `MultiTypeCapability` itself builds from `mekanism:chemical_handler` - a
 * sided block capability and a context-free item one - so this stays correct as long as that name
 * does, and fails loudly (nothing is ever found) rather than silently wrongly if it changes.
 */
object MekanismChemicals {
	private val ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler")

	/** The block-side handler, sided exactly as Mekanism registers it. */
	val BLOCK: BlockCapability<IChemicalHandler, Direction?> =
		BlockCapability.createSided(ID, IChemicalHandler::class.java)

	/** The item-side handler - what a chemical tank item exposes, and what the terminal's transfer slot asks. */
	val ITEM: ItemCapability<IChemicalHandler, Void?> =
		ItemCapability.createVoid(ID, IChemicalHandler::class.java)
}
