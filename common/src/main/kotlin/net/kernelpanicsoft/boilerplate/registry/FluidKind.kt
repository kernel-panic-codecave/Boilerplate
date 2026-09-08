package net.kernelpanicsoft.boilerplate.registry

import dev.architectury.extensions.injected.InjectedRegistryEntryExtension
import dev.architectury.fluid.FluidStack
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.fluid.util.FluidAmounts
import kotlinx.serialization.KSerializer
import net.kernelpanicsoft.archie.registries.holder
import net.kernelpanicsoft.boilerplate.network.FluidResourceSerializer
import net.kernelpanicsoft.boilerplate.network.ResourceKind
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.level.material.Fluid

/**
 * Fluids, as a [ResourceKind] - registered as `fluid` by [ResourceKindRegistry]. See [ItemKind] for
 * why these are named objects.
 */
@Suppress("UNCHECKED_CAST")
object FluidKind : ResourceKind() {
	override val kindTag: String get() = "fluid"

	override val resourceClass: Class<out ResourceComponent> get() = FluidResource::class.java

	override val storage get() = FluidStorageKind

	override val serializer: KSerializer<ResourceComponent> get() = FluidResourceSerializer as KSerializer<ResourceComponent>

	override fun displayName(resource: ResourceComponent): Component =
		(resource as? FluidResource)?.let { FluidStack.create(it.type, 1L).name } ?: super.displayName(resource)

	/**
	 * [FluidResource] overrides neither `equals` nor `hashCode` in Common Storage Lib 0.0.5, so it
	 * cannot be used as a map key as-is - see [ResourceKind.identityOf]. Its two defining parts both
	 * compare properly on their own: the [Fluid] is a registry singleton, and `DataComponentPatch`
	 * is a value type, so the pair of them is the identity the resource itself should have had.
	 */
	override fun identityOf(resource: ResourceComponent): Any {
		val fluid = resource as? FluidResource ?: return resource
		return fluid.type to fluid.dataPatch
	}

	override fun registryId(resource: ResourceComponent): ResourceLocation? =
		(resource as? FluidResource)?.let { BuiltInRegistries.FLUID.getKey(it.type) }

	override fun tagsOf(resource: ResourceComponent): List<TagKey<*>> =
		((resource as? FluidResource)?.type as InjectedRegistryEntryExtension<Fluid>?)?.holder?.tags()?.toList() ?: emptyList()

	/**
	 * Fluids are authored and shown in millibuckets and counted internally in whatever the platform
	 * uses - droplets on Fabric, millibuckets on NeoForge.
	 *
	 * Every one of these is computed on read rather than held as a constant, for the reason
	 * [net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity.getCapacity] documents:
	 * `FluidAmounts`' own constants all read `0` in Common Storage Lib 0.0.5, and a step or default
	 * of `0` is a cell that cannot be filled at all.
	 */
	override fun toAuthored(amount: Long): Long = FluidAmounts.toMillibuckets(amount)

	override fun toPlatform(authored: Long): Long = FluidAmounts.toPlatformAmount(authored)

	override val defaultAuthored: Long get() = MILLIBUCKETS_PER_BUCKET

	override val authoredStep: Long get() = MILLIBUCKETS_PER_STEP

	override val maxAuthored: Long get() = MILLIBUCKETS_PER_BUCKET * 64L

	/** A getter, not an initialised property - see [ItemKind.display]. */
	override val display get() = FluidDisplayKind

	/** One bucket, in millibuckets - this kind's own authored unit. */
	private const val MILLIBUCKETS_PER_BUCKET = 1000L

	/**
	 * How far one scroll notch moves a fluid amount, in millibuckets.
	 *
	 * A 100mB notch reaches every amount real recipes actually use - 100, 250 (as 200/300 in two
	 * notches from either side is close enough to be worth the coarser step), 500, 1000 - in a
	 * handful of scrolls, where a 1mB notch would need a thousand of them for one bucket.
	 */
	private const val MILLIBUCKETS_PER_STEP = 100L
}
