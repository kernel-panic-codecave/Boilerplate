package net.kernelpanicsoft.boilerplate.registry

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.resource.ResourceKind
import net.kernelpanicsoft.boilerplate.resource.ResourceStorageKind
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.kernelpanicsoft.boilerplate.resource.FluidKind
import net.kernelpanicsoft.boilerplate.resource.ItemKind

/**
 * Registers Boilerplate's own carrier [ResourceKind]s (item, fluid) into the custom
 * [Registrars.RESOURCE_KIND] registry, and resolves a live [ResourceComponent] or wire kind tag to
 * its registered kind - the lookup half of [net.kernelpanicsoft.boilerplate.resource.ResourceStackSerializer]'s
 * dispatch. A loader/addon's own kind (Mekanism gas, say) registers by subclassing
 * [ADeferredRegistryHolder] over the *same* [Registrars.RESOURCE_KIND] registry key and calling
 * `init()` from its own setup - no edit to Boilerplate's holder or serializer needed - mirroring
 * exactly how it registers its [net.kernelpanicsoft.boilerplate.pipe.network.NetworkType] into
 * [Registrars.NETWORK_TYPE].
 *
 * The kinds themselves are named objects in files of their own ([ItemKind], [FluidKind]) rather than
 * declared inline here - each is dense enough to be worth reading on its own, and each sits beside
 * the [ResourceStorageKind] and display surface it points at.
 */
object ResourceKindRegistry : ADeferredRegistryHolder<ResourceKind>(
	Boilerplate.MOD,
	Registrars.RESOURCE_KIND.key() as ResourceKey<Registry<ResourceKind>>,
) {
	val Item: ResourceKind by register("item") { ItemKind }

	val Fluid: ResourceKind by register("fluid") { FluidKind }

	/**
	 * The registered [ResourceKind] whose [ResourceKind.resourceClass] is a supertype of [resource],
	 * or `null` if none is.
	 *
	 * Memoised on the resource's concrete class, because the answer depends on nothing else and this
	 * is among the hottest lookups in the mod: [net.kernelpanicsoft.boilerplate.resource.ResourceIdentity.of]
	 * goes through it for every identity it builds, including inside per-slot loops over a
	 * fifty-four-slot rack. Uncached, each of those is a walk of the registry doing a reflective
	 * `isInstance` per kind.
	 *
	 * Only *hits* are remembered, which is what makes the memo safe against an addon registering a
	 * kind late: a miss is re-resolved every time, so the first call after that registration finds
	 * it. A hit cannot go stale - two kinds claiming the same resource class would be a conflict in
	 * its own right, and registration order is fixed once the registry has loaded.
	 */
	fun forResource(resource: ResourceComponent): ResourceKind? {
		kindByResourceClass[resource.javaClass]?.let { return it }
		for (kind in Registrars.RESOURCE_KIND) if (kind.resourceClass.isInstance(resource)) {
			kindByResourceClass[resource.javaClass] = kind
			return kind
		}
		return null
	}

	/** [forResource]'s memo, keyed by the concrete class asked about. */
	private val kindByResourceClass = ConcurrentHashMap<Class<*>, ResourceKind>()

	/**
	 * Every registered kind that can live in a storage, in registry order - what the warehouse
	 * iterates instead of naming items and fluids.
	 *
	 * Recomputed per call rather than cached: the registry is small, and a cache would have to be
	 * invalidated on late registration, which addons legitimately do.
	 */
	fun storageKinds(): List<ResourceKind> = Registrars.RESOURCE_KIND.filter { it.storage != null }

	/**
	 * Every registered kind that has visuals of its own, in registry order - what a screen iterates
	 * when it has to ask *every* kind something rather than resolve one resource's own.
	 *
	 * Registry order matters to at least one caller: reading what the player is carrying asks each
	 * kind in turn, and the item kind claims any stack at all, so it must be asked last. It is
	 * registered first here and the list is reversed for exactly that reason.
	 */
	fun displayKinds(): List<ResourceKind> = Registrars.RESOURCE_KIND.filter { it.display != null }.reversed()

	/**
	 * [storageKinds] minus [excluded] - what a block that keeps a dedicated field for some kind
	 * passes to its own [net.kernelpanicsoft.boilerplate.resource.ResourceStorage], so a resource of
	 * that kind can never land somewhere the block will not look for it.
	 */
	fun storageKindsExcept(vararg excluded: ResourceKind): List<ResourceKind> =
		storageKinds().filter { kind -> excluded.none { it === kind } }

	/** [resource]'s own kind's storage surface, or `null` if its kind is unregistered or cannot be stored. */
	fun storageFor(resource: ResourceComponent): ResourceStorageKind? = forResource(resource)?.storage

	/** The registered [ResourceKind] with [kindTag], or null if none is. */
	fun byTag(kindTag: String): ResourceKind? {
		for (kind in Registrars.RESOURCE_KIND) if (kind.kindTag == kindTag) return kind
		return null
	}
}
