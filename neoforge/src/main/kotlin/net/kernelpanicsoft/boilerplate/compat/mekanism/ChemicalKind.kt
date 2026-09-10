package net.kernelpanicsoft.boilerplate.compat.mekanism

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import kotlinx.serialization.KSerializer
import net.kernelpanicsoft.boilerplate.resource.ResourceKind
import net.kernelpanicsoft.boilerplate.resource.ResourceMeasure
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey

/**
 * Mekanism chemicals, as a [ResourceKind] - registered as `chemical` by [ChemicalResourceKindRegistry].
 *
 * A named object in a file of its own, the same shape
 * [net.kernelpanicsoft.boilerplate.resource.ItemKind] and
 * [net.kernelpanicsoft.boilerplate.resource.FluidKind] take, and beside the [ChemicalStorageKind]
 * and [ChemicalDisplayKind] it points at.
 *
 * NeoForge-only, because Mekanism is. Nothing in `common` knows this exists.
 */
object ChemicalKind : ResourceKind() {
	override val kindTag: String get() = "chemical"

	override val resourceClass: Class<out ResourceComponent> get() = ChemicalResource::class.java

	/** Measured in the same millibuckets a fluid is - see [ResourceMeasure.UNIT]. */
	override val measure get() = ResourceMeasure.UNIT

	override val storage get() = ChemicalStorageKind

	/** A getter, not an initialised property: [ChemicalDisplayKind] is client-only, like every other kind's. */
	override val display get() = ChemicalDisplayKind

	override val serializer: KSerializer<ResourceComponent> get() = ChemicalResourceSerializer

	/**
	 * A chemical carries no per-stack data, so the chemical itself *is* the identity - and it is a
	 * registry singleton, which already compares correctly. Spelled out rather than inherited so the
	 * contract does not rest on that.
	 */
	override fun identityOf(resource: ResourceComponent): Any =
		(resource as? ChemicalResource)?.chemical ?: resource

	override fun registryId(resource: ResourceComponent): ResourceLocation? =
		(resource as? ChemicalResource)?.takeIf { !it.isBlank }?.chemical?.registryName

	override fun displayName(resource: ResourceComponent): Component =
		(resource as? ChemicalResource)?.takeIf { !it.isBlank }?.chemical?.textComponent
			?: super.displayName(resource)

	/** Mekanism exposes a chemical's tags directly, so unlike the item and fluid kinds this needs no Architectury registry-entry injection to reach them. */
	override fun tagsOf(resource: ResourceComponent): List<TagKey<*>> =
		(resource as? ChemicalResource)?.takeIf { !it.isBlank }?.chemical?.asHolder?.tags()?.toList() ?: emptyList()

	/**
	 * Mekanism counts chemicals in millibuckets directly, with no platform conversion of the kind a
	 * fluid needs - so authored and stored amounts are the same number, and only the step and
	 * defaults differ from an item's.
	 */
	override val defaultAuthored: Long get() = 1_000L

	override val authoredStep: Long get() = 100L

	override val maxAuthored: Long get() = 64_000L
}
