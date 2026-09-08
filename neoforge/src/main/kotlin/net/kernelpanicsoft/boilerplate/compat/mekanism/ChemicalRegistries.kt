package net.kernelpanicsoft.boilerplate.compat.mekanism

import com.mojang.serialization.Codec
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import mekanism.api.MekanismAPI
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.serialization.CodecSerializer
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.network.ResourceKind
import net.kernelpanicsoft.boilerplate.pipe.network.NetworkType
import net.kernelpanicsoft.boilerplate.registry.Registrars
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation

/**
 * Registers Mekanism's chemicals as a Boilerplate [ResourceKind], so pipes carry them, the warehouse
 * stores them, the Crafting CPU stages them and the terminal lists them - none of which needed an
 * edit to make this work.
 *
 * The first real test of the kind registry as an extension point, and the shape an addon is meant to
 * follow: subclass [ADeferredRegistryHolder] over the *same* [Registrars.RESOURCE_KIND] registry
 * Boilerplate's own item and fluid kinds use, and call [init] from a setup that only runs when
 * Mekanism is actually present.
 *
 * NeoForge-only, because Mekanism is. Nothing in `common` knows this exists.
 *
 * The kind itself is [ChemicalKind], a named object in a file of its own - see it for why.
 */
object ChemicalResourceKindRegistry : ADeferredRegistryHolder<ResourceKind>(
	Boilerplate.MOD,
	@Suppress("UNCHECKED_CAST")
	(Registrars.RESOURCE_KIND.key() as ResourceKey<Registry<ResourceKind>>),
) {
	val Chemical: ResourceKind by register("mekanism" % "chemical") { ChemicalKind }
}

/**
 * Registers the chemical **pipe network** - the half that makes a chemical actually move.
 *
 * Separate from [ChemicalResourceKindRegistry] because Boilerplate keeps the two concerns in two registries:
 * a [ResourceKind] makes a resource storable, craftable and listable, while a
 * [net.kernelpanicsoft.boilerplate.pipe.network.NetworkType] makes it transportable. Registering
 * only the first is easy to do and produces a kind that is stored perfectly and never goes anywhere.
 */
object ChemicalNetworkTypeRegistry : ADeferredRegistryHolder<NetworkType>(
	Boilerplate.MOD,
	@Suppress("UNCHECKED_CAST")
	(Registrars.NETWORK_TYPE.key() as ResourceKey<Registry<NetworkType>>),
) {
	val Chemical: NetworkType by register(ChemicalNetworkType.ID) { ChemicalNetworkType }
}

/**
 * A chemical on the wire, by registry name.
 *
 * Mekanism's own `ChemicalStack.CODEC` carries an amount, which a *resource* must not - a resource
 * is the identity alone, and the amount rides beside it in a
 * [earth.terrarium.common_storage_lib.resources.ResourceStack].
 */
object ChemicalResourceSerializer : CodecSerializer<ResourceComponent>(
    ResourceLocation.CODEC.xmap(
        { id -> ChemicalResource.of(MekanismAPI.CHEMICAL_REGISTRY.get(id)) as ResourceComponent },
        { resource -> (resource as? ChemicalResource)?.chemical?.registryName ?: MekanismAPI.EMPTY_CHEMICAL_NAME },
    ) as Codec<ResourceComponent>,
)
