package net.kernelpanicsoft.boilerplate.resource

import dev.architectury.extensions.injected.InjectedRegistryEntryExtension
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.KSerializer
import net.kernelpanicsoft.archie.registries.holder
import net.kernelpanicsoft.boilerplate.resource.ItemResourceSerializer
import net.kernelpanicsoft.boilerplate.resource.ResourceKind
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.kernelpanicsoft.boilerplate.client.ItemDisplayKind

/**
 * Items, as a [ResourceKind] - registered as `item` by [ResourceKindRegistry].
 *
 * A named object rather than one declared inline at its registration, so it can be referred to
 * directly (its own [ItemStorageKind] and [ItemDisplayKind] do), read on its own, and sit beside the
 * two surfaces it points at rather than inside the registry that hands it out.
 */
@Suppress("UNCHECKED_CAST")
object ItemKind : ResourceKind() {
	override val kindTag: String get() = "item"

	override val resourceClass: Class<out ResourceComponent> get() = ItemResource::class.java

	/** One item is one whole thing, and there is no half of one - see [ResourceMeasure.DISCRETE]. */
	override val measure get() = ResourceMeasure.DISCRETE

	/** The item alone - `ItemResource`'s own equality is item-plus-components, and this is the half that ignores them. */
	override fun baseIdentityOf(resource: ResourceComponent): Any =
		(resource as? ItemResource)?.item ?: identityOf(resource)

	override val storage get() = ItemStorageKind

	override val serializer: KSerializer<ResourceComponent> get() = ItemResourceSerializer as KSerializer<ResourceComponent>

	override fun displayName(resource: ResourceComponent): Component =
		(resource as? ItemResource)?.cachedStack?.hoverName ?: super.displayName(resource)

	override fun registryId(resource: ResourceComponent): ResourceLocation? =
		(resource as? ItemResource)?.let { BuiltInRegistries.ITEM.getKey(it.item) }

	override fun tagsOf(resource: ResourceComponent): List<TagKey<*>> =
		((resource as? ItemResource)?.item as InjectedRegistryEntryExtension<Item>?)?.holder?.tags()?.toList() ?: emptyList()

	/** An item stack renders its own count, so nothing else should write one beside it. */
	override val drawsOwnAmount: Boolean get() = true

	/** A getter, not an initialised property: [ItemDisplayKind] is client-only and must not load on a server. */
	override val display get() = ItemDisplayKind

	/**
	 * One whole of an item is a full stack **of that item** - 64 cobblestone, 16 ender pearls, one
	 * shulker box - so an item that stacks to fewer costs proportionally more of a shared pool.
	 *
	 * Read from the resource's own stack rather than from the item's default, since data components
	 * can change it ([net.minecraft.core.component.DataComponents.MAX_STACK_SIZE]), and floored at
	 * `1` so a nonsensical component value cannot make a resource free to store.
	 */
	override fun wholeAuthored(resource: ResourceComponent): Long =
		((resource as? ItemResource)?.cachedStack?.maxStackSize ?: 1).toLong().coerceAtLeast(1L)

	/** Vanilla crafting is defined over item stacks, so the item kind is the one that fits in a grid. */
	override val vanillaCraftable: Boolean get() = true

	override fun toVanillaStack(resource: ResourceComponent, amount: Long): ItemStack? =
		(resource as? ItemResource)?.takeIf { !it.isBlank }?.toStack(amount.toInt().coerceAtLeast(1))
}
