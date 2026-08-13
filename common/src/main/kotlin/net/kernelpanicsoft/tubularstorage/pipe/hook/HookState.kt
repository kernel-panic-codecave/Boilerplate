package net.kernelpanicsoft.tubularstorage.pipe.hook

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SResourceLocation

/**
 * Persisted state for one [PipeHookType] attached to one face of a
 * [net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity]. [type] is the attached hook's
 * full [net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry] id (e.g.
 * [ExtractionHookType.ID]/[SortingHookType.ID]) - a real `ResourceLocation` rather than an
 * unqualified name, so an addon mod's own [PipeHookType] (registered under its own namespace)
 * round-trips correctly too. [ticksSinceExtraction] is only meaningful for an extraction hook's
 * own cooldown; a sorting hook ignores it. Deliberately excludes the face's
 * [net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule] - that lives in
 * [net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity]'s own
 * [net.kernelpanicsoft.tubularstorage.pipe.entity.FaceRouting]-typed `routing` field instead,
 * since `@Sync` doesn't currently work correctly on a map-valued
 * [net.kernelpanicsoft.archie.serialization.NBTHolder] field - see `docs/design/m1-pipe-network.md`.
 */
@Serializable
data class HookState(
	val type: SResourceLocation,
	val ticksSinceExtraction: Int = 0,
)
