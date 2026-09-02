package net.kernelpanicsoft.boilerplate.registry

import dev.architectury.extensions.injected.InjectedRegistryEntryExtension
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.KSerializer
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.registries.holder
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.network.FluidResourceSerializer
import net.kernelpanicsoft.boilerplate.network.ItemResourceSerializer
import net.kernelpanicsoft.boilerplate.network.ResourceKind
import net.kernelpanicsoft.boilerplate.network.ResourceStorageKind
import net.minecraft.core.Registry
import net.minecraft.network.chat.Component
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.level.material.Fluid

/**
 * Registers Boilerplate's own carrier [ResourceKind]s (item, fluid) into the custom
 * [Registrars.RESOURCE_KIND] registry, and resolves a live [ResourceComponent] or wire kind tag to
 * its registered kind - the lookup half of [net.kernelpanicsoft.boilerplate.network.ResourceStackSerializer]'s
 * dispatch. A loader/addon's own kind (Mekanism gas, say) registers by subclassing
 * [ADeferredRegistryHolder] over the *same* [Registrars.RESOURCE_KIND] registry key and calling
 * `init()` from its own setup - no edit to Boilerplate's holder or serializer needed - mirroring
 * exactly how it registers its [net.kernelpanicsoft.boilerplate.pipe.network.NetworkType] into
 * [Registrars.NETWORK_TYPE].
 */
@Suppress("UNCHECKED_CAST")
object ResourceKindRegistry : ADeferredRegistryHolder<ResourceKind>(
	Boilerplate.MOD,
	Registrars.RESOURCE_KIND.key() as ResourceKey<Registry<ResourceKind>>,
) {
	val Item: ResourceKind by register("item") {
		object : ResourceKind() {
			override val kindTag: String get() = "item"
			override val resourceClass: Class<out ResourceComponent> get() = ItemResource::class.java
			override val storage get() = ItemStorageKind
			override val serializer: KSerializer<ResourceComponent> get() = ItemResourceSerializer as KSerializer<ResourceComponent>

			override fun displayName(resource: ResourceComponent): Component =
				(resource as? ItemResource)?.cachedStack?.hoverName ?: super.displayName(resource)

			override fun registryId(resource: ResourceComponent): ResourceLocation? =
				(resource as? ItemResource)?.let { BuiltInRegistries.ITEM.getKey(it.item) }

			override fun tagsOf(resource: ResourceComponent): List<TagKey<*>> =
				((resource as? ItemResource)?.item as InjectedRegistryEntryExtension<Item>?)?.holder?.tags()?.toList() ?: emptyList()
		}
	}
	val Fluid: ResourceKind by register("fluid") {
		object : ResourceKind() {
			override val kindTag: String get() = "fluid"
			override val resourceClass: Class<out ResourceComponent> get() = FluidResource::class.java
			override val storage get() = FluidStorageKind
			override val serializer: KSerializer<ResourceComponent> get() = FluidResourceSerializer as KSerializer<ResourceComponent>

			/**
			 * [FluidResource] overrides neither `equals` nor `hashCode` in Common Storage Lib 0.0.5,
			 * so it cannot be used as a map key as-is - see [ResourceKind.identityOf]. Its two
			 * defining parts both compare properly on their own: the [net.minecraft.world.level.material.Fluid]
			 * is a registry singleton, and `DataComponentPatch` is a value type, so the pair of them
			 * is the identity the resource itself should have had.
			 */
			override fun identityOf(resource: ResourceComponent): Any {
				val fluid = resource as? FluidResource ?: return resource
				return fluid.type to fluid.dataPatch
			}

			override fun registryId(resource: ResourceComponent): ResourceLocation? =
				(resource as? FluidResource)?.let { BuiltInRegistries.FLUID.getKey(it.type) }

			override fun tagsOf(resource: ResourceComponent): List<TagKey<*>> =
				((resource as? FluidResource)?.type as InjectedRegistryEntryExtension<Fluid>?)?.holder?.tags()?.toList() ?: emptyList()
		}
	}

	/** The registered [ResourceKind] whose [ResourceKind.resourceClass] is a supertype of [resource], or null if none is. */
	fun forResource(resource: ResourceComponent): ResourceKind? {
		for (kind in Registrars.RESOURCE_KIND) if (kind.resourceClass.isInstance(resource)) return kind
		return null
	}

	/**
	 * Every registered kind that can live in a storage, in registry order - what the warehouse
	 * iterates instead of naming items and fluids.
	 *
	 * Recomputed per call rather than cached: the registry is small, and a cache would have to be
	 * invalidated on late registration, which addons legitimately do.
	 */
	fun storageKinds(): List<ResourceKind> = Registrars.RESOURCE_KIND.filter { it.storage != null }

	/** [resource]'s own kind's storage surface, or `null` if its kind is unregistered or cannot be stored. */
	fun storageFor(resource: ResourceComponent): ResourceStorageKind? = forResource(resource)?.storage

	/** The registered [ResourceKind] with [kindTag], or null if none is. */
	fun byTag(kindTag: String): ResourceKind? {
		for (kind in Registrars.RESOURCE_KIND) if (kind.kindTag == kindTag) return kind
		return null
	}
}
