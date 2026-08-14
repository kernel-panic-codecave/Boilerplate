package net.kernelpanicsoft.tubularstorage.pipe.hook

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SResourceLocation
import net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule

/**
 * Persisted state for one [PipeHookType] attached to one face of a
 * [net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity]. [type] is the attached hook's
 * full [net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry] id (e.g.
 * [ExtractionHookType.ID]/[SortingHookType.ID]) - a real `ResourceLocation` rather than an
 * unqualified name, so an addon mod's own [PipeHookType] (registered under its own namespace)
 * round-trips correctly too. [routing] and [ticksSinceExtraction] are shared storage interpreted
 * differently per hook type - an extraction hook only reads [routing]'s `color` (to tag what it
 * sends out) and owns its own [ticksSinceExtraction] cooldown; a sorting hook reads all of
 * [routing] (mode/priority/color) against the face's filter grid and ignores [ticksSinceExtraction].
 */
@Serializable
data class HookState(
	val type: SResourceLocation,
	val routing: RoutingModule = RoutingModule(),
	val ticksSinceExtraction: Int = 0,
)
