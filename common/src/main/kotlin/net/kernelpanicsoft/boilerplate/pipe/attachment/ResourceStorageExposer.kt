package net.kernelpanicsoft.boilerplate.pipe.attachment

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.boilerplate.network.ResourceKind
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.PassThroughStorage
import net.minecraft.core.Direction

/**
 * A hook or encasement that exposes a storage surface for **any** registered [ResourceKind] - the
 * kind-generic counterpart of [ItemStorageExposer]/[FluidStorageExposer].
 *
 * Those two exist as their own interfaces because their return types are concrete
 * (`CommonStorage<ItemResource>` is what an `ItemApi.BLOCK` provider must hand back) and because
 * items and fluids are the two kinds `common` can name. A kind registered by a loader or an addon -
 * Mekanism's chemicals - has neither: nothing in `common` can write a typed method for it, and
 * adding one per kind is exactly the hardcoding the kind registry exists to remove. So a state that
 * can hold *whatever it is handed* implements this instead, once, and every future kind is covered.
 *
 * Only ever asked for a kind that has no dedicated exposer of its own; items and fluids keep going
 * through theirs, which is what the typed capabilities need.
 */
interface ResourceStorageExposer {
	/** This state's storage of [kind] on the segment it is attached to, or `null` if it holds none. */
	fun exposedStorage(tile: MultipartBlockEntity, kind: ResourceKind): CommonStorage<*>?
}

/**
 * A [ResourceStorageExposer] additionally eligible for a direction-less query's any-face fallback -
 * the kind-generic counterpart of [FallbackItemStorageExposer], and eligible for exactly the same
 * reasons; see that interface for why a state may deliberately decline to be.
 */
interface FallbackResourceStorageExposer : ResourceStorageExposer

/**
 * The storage this segment exposes for [kind] when reached from [direction], resolved through the
 * same three tiers [net.kernelpanicsoft.boilerplate.registry.TileRegistry.Multipart]'s own item and
 * fluid lookups use - the hook on that exact face first, then the whole-segment encasement, then any
 * [FallbackResourceStorageExposer] hook on any face.
 *
 * Written once here rather than per kind, so a loader registering its own kind's capability (see
 * `BoilerplateNeoForge`'s chemical registration) gets the identical resolution order for free
 * instead of reimplementing it and drifting.
 */
@Suppress("UNCHECKED_CAST")
fun <T : ResourceComponent> MultipartBlockEntity.exposedStorageFor(kind: ResourceKind, direction: Direction?): CommonStorage<T>? {
	val hookAtFace = direction?.let { hooks[it.name] }
	val exposed = (hookAtFace as? ResourceStorageExposer)?.exposedStorage(this, kind)
		?: (encasement.value as? ResourceStorageExposer)?.exposedStorage(this, kind)
		?: hooks.firstNotNullOfOrNull { (it.value as? FallbackResourceStorageExposer)?.exposedStorage(this, kind) }
	// The same pass-through fallback the item and fluid capabilities have had all along (see
	// [net.kernelpanicsoft.boilerplate.registry.TileRegistry]), and the reason this is here rather
	// than duplicated per kind: without it a loader-registered kind saw *no* capability at all on
	// any face without a hook that knew about it, so a Mekanism machine pointed at a bare pipe
	// found nothing to push its chemicals into. Every face accepts a push - that is the rule, and
	// it cannot be one that only the two kinds this mod happens to ship in `common` get.
	@Suppress("UNCHECKED_CAST")
	return (exposed ?: direction?.let { PassThroughStorage.of(this, it, kind) }) as CommonStorage<T>?
}
