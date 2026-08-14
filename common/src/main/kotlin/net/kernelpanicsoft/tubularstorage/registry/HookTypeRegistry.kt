package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState
import net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookType
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation

/** Registers Tubular Storage's [PipeHookType]s into the custom registry [HookTypeRegistrar] declares. */
@Suppress("UNCHECKED_CAST")
object HookTypeRegistry : ADeferredRegistryHolder<PipeHookType<out HookHolderState>>(
	TubularStorage.MOD,
	HookTypeRegistrar.HOOK_TYPE.key() as ResourceKey<Registry<PipeHookType<out HookHolderState>>>,
) {
	val Extraction: PipeHookType<ExtractionHookState> by register(ExtractionHookType.ID) { ExtractionHookType }
	val Sorting: PipeHookType<SortingHookState> by register(SortingHookType.ID) { SortingHookType }
	val Provider: PipeHookType<ProviderHookState> by register(ProviderHookType.ID) { ProviderHookType }
	val Requester: PipeHookType<RequesterHookState> by register(RequesterHookType.ID) { RequesterHookType }

	/**
	 * Looks up a registered [PipeHookType] by its full id (e.g.
	 * [net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState.type]) - a real
	 * `ResourceLocation`, not assumed to be namespaced under Tubular Storage, so an addon mod's own
	 * hook type resolves correctly too. Goes through the live
	 * [Registrar][net.kernelpanicsoft.archie.registries.RegistrarHelper] rather than this holder's
	 * own bookkeeping [Map] - the latter is populated at [register] call time regardless of whether
	 * the underlying custom registry has actually processed that registration yet, so it isn't a
	 * reliable source for a runtime, id-keyed lookup.
	 */
	fun byId(id: ResourceLocation): PipeHookType<HookHolderState>? = HookTypeRegistrar.HOOK_TYPE.get(id) as? PipeHookType<HookHolderState>
}
