package net.kernelpanicsoft.boilerplate.network

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import kotlinx.serialization.KSerializer
import net.kernelpanicsoft.boilerplate.client.ResourceDisplayKind
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.ItemStack

/**
 * A kind of carrier resource that [ResourceStackSerializer] can put on the wire - the item and
 * fluid kinds Boilerplate ships with, and (via a loader/addon registrar) an addon's own kind
 * (Mekanism gas, say). A real registry entry (see
 * [ResourceKindRegistry]/[net.kernelpanicsoft.boilerplate.registry.Registrars.RESOURCE_KIND])
 * rather than a hardcoded case in [ResourceStackSerializer]'s dispatch, so a new kind is just
 * another registered `(kindTag, resourceClass, serializer)` triple - the addon subclasses
 * [net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder] over the same
 * [net.kernelpanicsoft.boilerplate.registry.Registrars.RESOURCE_KIND] registry, exactly like the
 * `network_type`-keyed holder it registers its [net.kernelpanicsoft.boilerplate.pipe.network.NetworkType]
 * through.
 *
 * [kindTag] is the leading string tag [ResourceStackSerializer] writes before a stack's resource
 * and reads back to pick the serializer - it must be unique across every registered kind. The
 * [serializer] is the wire/CODEC form of the resource itself (usually a
 * [net.kernelpanicsoft.archie.serialization.CodecSerializer] over the resource's own
 * [com.mojang.serialization.Codec]).
 */
abstract class ResourceKind {
	/** This kind's own unique wire tag - what [ResourceStackSerializer] keys a stack's resource on. */
	abstract val kindTag: String

	/** The concrete [ResourceComponent] subclass this kind carries - used to resolve a resource to its kind ([ResourceKindRegistry.forResource]). */
	abstract val resourceClass: Class<out ResourceComponent>

	/** The serializer for a resource of this kind, typed up to the generic [ResourceComponent] surface [ResourceStackSerializer] works in terms of. */
	abstract val serializer: KSerializer<ResourceComponent>

	/**
	 * A value-comparable stand-in for [resource] - two resources that mean the same thing must
	 * return `identityOf` values that are `equals` and share a hash code.
	 *
	 * Exists because [ResourceComponent] does **not** guarantee value equality across kinds.
	 * `ItemResource` overrides `equals`/`hashCode` on type-plus-components;
	 * `FluidResource` (Common Storage Lib 0.0.5) overrides neither, so two
	 * `FluidResource.of(Fluids.WATER)` instances are unequal and hash differently. Anything that
	 * keys a map or a cache on a resource - [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter]'s
	 * route cache, [net.kernelpanicsoft.boilerplate.warehouse.WarehouseIndex]'s stock table - is
	 * silently broken for such a kind: every lookup misses and every insert grows the map.
	 *
	 * The default returns [resource] itself, which is correct for any kind that already has value
	 * equality. A kind whose resource type doesn't override it must say what its identity is
	 * instead - see [ResourceKindRegistry]'s fluid entry.
	 * Always reached through [ResourceIdentity.of] rather than called directly.
	 */
	open fun identityOf(resource: ResourceComponent): Any = resource

	/**
	 * [resource]'s own registry id (`minecraft:water`, `minecraft:diamond`), or `null` if this kind
	 * cannot name it.
	 *
	 * What lets the kind-agnostic filter conditions work on any registered kind:
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.ModConditionType] reads its namespace and
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.RegexConditionType] matches against the
	 * whole thing, neither of which has any reason to care whether it is holding an item or a fluid.
	 * [ResourceComponent] itself exposes only data components, so this has to come from the kind.
	 */
	abstract fun registryId(resource: ResourceComponent): ResourceLocation?

	/**
	 * How this kind's storage is reached and moved through, or `null` if this kind cannot live in a
	 * storage at all.
	 *
	 * Registering a kind that provides one is all it takes to make that kind **warehouse-able**:
	 * any block inside a bound volume exposing it becomes a rack, and gantry retrieval, put-away and
	 * defragmentation all start working for it without an edit anywhere in the warehouse. See
	 * [ResourceStorageKind].
	 */
	open val storage: ResourceStorageKind? get() = null

	/**
	 * How this kind is drawn - in a slot, in a pipe, on the crane - or `null` if it has no visuals
	 * of its own, in which case nothing draws it.
	 *
	 * **Read only from client code.** The property exists on the common kind so that registering a
	 * kind is the single thing an addon has to do, but the implementation it returns lives in a
	 * client class that a dedicated server never loads, because nothing there ever reads this.
	 *
	 * @see net.kernelpanicsoft.boilerplate.client.ResourceDisplayKind
	 */
	open val display: ResourceDisplayKind? get() = null

	/**
	 * A human-readable name for [resource] - what job status lines, tooltips and error text show.
	 *
	 * Defaults to the path of [registryId] (`water`, `diamond`), which is legible for any kind that
	 * has not said otherwise; a kind whose resources carry a real translated name should override
	 * with it - see the item kind's own, which reuses the stack's `hoverName`.
	 */
	open fun displayName(resource: ResourceComponent): Component =
		Component.literal(registryId(resource)?.path ?: resource.toString())

	/**
	 * [amount], converted from the platform's own count into the unit a player authors and reads.
	 *
	 * The two are the same number for a counted kind and are not for a measured one: a fluid is
	 * authored in **millibuckets**, which is the unit a player thinks in, while the platform counts
	 * droplets on Fabric and millibuckets on NeoForge. Every screen that shows or edits an amount
	 * goes through here rather than asking what it is holding, so a registered kind brings its own
	 * unit with it - see [toPlatform] for the way back.
	 */
	open fun toAuthored(amount: Long): Long = amount

	/** [authored], converted back into the platform's own count - [toAuthored]'s inverse, applied at the boundary where a durable amount is stored. */
	open fun toPlatform(authored: Long): Long = authored

	/**
	 * One authored unit of this kind - what a cell holding it defaults to when nothing better is
	 * known. One item; one bucket.
	 */
	open val defaultAuthored: Long get() = 1L

	/** How far one scroll notch moves an authored amount of this kind - one item, or 100mB. */
	open val authoredStep: Long get() = 1L

	/** The largest authored amount a single pattern cell of this kind holds - a stack, or 64 buckets. */
	open val maxAuthored: Long get() = 64L

	/**
	 * Whether a slot of this kind draws its own amount.
	 *
	 * An item stack renders its count itself, so a caller that also drew one would double it; a
	 * fluid's still sprite carries no number at all and needs the amount written beside it. A
	 * screen asks this instead of asking what it is holding.
	 */
	open val drawsOwnAmount: Boolean get() = false

	/**
	 * Whether a **vanilla** crafting grid can hold this kind at all.
	 *
	 * Vanilla recipes, the crafting table block and `CraftingInput` are all defined over
	 * [net.minecraft.world.item.ItemStack] and nothing else, so a kind that is not an item form
	 * cannot be an ingredient of, or a result of, a vanilla recipe - no matter how well the rest of
	 * the mod carries it. Everything that touches a real crafting table asks this rather than
	 * checking what it is holding, and a kind that says `true` must answer [toVanillaStack].
	 */
	open val vanillaCraftable: Boolean get() = false

	/**
	 * [amount] of [resource] as the vanilla [net.minecraft.world.item.ItemStack] a crafting grid
	 * would hold, or `null` for a kind with no item form - see [vanillaCraftable].
	 */
	open fun toVanillaStack(resource: ResourceComponent, amount: Long): ItemStack? = null

	/**
	 * Every tag [resource] belongs to, for
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.TagConditionType].
	 *
	 * Returning the whole set rather than an `isIn(tag)` predicate deliberately: that condition
	 * supports `*` wildcards and has to enumerate to resolve them, and an exact match is then just a
	 * search of the same list - so one method serves both instead of the kind having to know the
	 * registry a [net.minecraft.tags.TagKey] would need to be built against.
	 */
	abstract fun tagsOf(resource: ResourceComponent): List<TagKey<*>>
}

/**
 * [resource]'s own human-readable name via its registered kind (see [ResourceKind.displayName]),
 * falling back to its `toString` for a resource of no registered kind at all - status text should
 * degrade to something ugly rather than throw.
 */
fun ResourceComponent.displayName(): Component =
	ResourceKindRegistry.forResource(this)?.displayName(this)
		?: Component.literal(toString())
