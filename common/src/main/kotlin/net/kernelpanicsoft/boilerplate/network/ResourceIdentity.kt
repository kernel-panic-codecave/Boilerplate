package net.kernelpanicsoft.boilerplate.network

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry

/**
 * A [ResourceComponent] wrapped into something safe to use as a map or cache key.
 *
 * Resources are *not* uniformly value-comparable: `ItemResource` overrides `equals`/`hashCode`,
 * `FluidResource` (Common Storage Lib 0.0.5) does not, so a raw fluid used as a `HashMap` key never
 * matches itself and every insert grows the map without bound. Rather than teach every call site
 * that asymmetry, the key is made explicit - build one with [of] and compare those.
 *
 * Equality is delegated to the resource's own registered [ResourceKind.identityOf], so a kind whose
 * resource type gains real equality later (or an addon kind that always had it) needs no change
 * here; and an unregistered resource falls back to its own identity rather than throwing, which
 * degrades to today's behaviour instead of taking a tick loop down.
 *
 * [resource] is kept alongside the identity so a holder can recover the real resource it was keyed
 * by - callers iterating a keyed map need the resource back, not just its identity.
 */
class ResourceIdentity private constructor(val resource: ResourceComponent, private val identity: Any) {
	override fun equals(other: Any?): Boolean = this === other || (other is ResourceIdentity && identity == other.identity)

	override fun hashCode(): Int = identity.hashCode()

	override fun toString(): String = "ResourceIdentity($resource)"

	companion object {
		fun of(resource: ResourceComponent): ResourceIdentity =
			ResourceIdentity(resource, ResourceKindRegistry.forResource(resource)?.identityOf(resource) ?: resource)
	}
}
