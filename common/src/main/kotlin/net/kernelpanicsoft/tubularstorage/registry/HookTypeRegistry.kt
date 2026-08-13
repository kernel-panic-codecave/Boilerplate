package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookType
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation

/** Registers Tubular Storage's [PipeHookType]s into the custom registry [HookTypeRegistrar] declares. */
@Suppress("UNCHECKED_CAST")
object HookTypeRegistry : ADeferredRegistryHolder<PipeHookType>(
	TubularStorage.MOD,
	HookTypeRegistrar.HOOK_TYPE.key() as ResourceKey<Registry<PipeHookType>>,
) {
	val Extraction: PipeHookType by register(ExtractionHookType.ID) { ExtractionHookType }
	val Sorting: PipeHookType by register(SortingHookType.ID) { SortingHookType }

	/**
	 * Looks up a registered [PipeHookType] by its full id (e.g. [HookState.type]) - a real
	 * `ResourceLocation`, not assumed to be namespaced under Tubular Storage, so an addon mod's own
	 * hook type resolves correctly too. Goes through the live
	 * [Registrar][net.kernelpanicsoft.archie.registries.RegistrarHelper] rather than this holder's
	 * own bookkeeping [Map] - the latter is populated at [register] call time regardless of whether
	 * the underlying custom registry has actually processed that registration yet, so it isn't a
	 * reliable source for a runtime, id-keyed lookup.
	 */
	fun byId(id: ResourceLocation): PipeHookType? = HookTypeRegistrar.HOOK_TYPE.get(id)
}
