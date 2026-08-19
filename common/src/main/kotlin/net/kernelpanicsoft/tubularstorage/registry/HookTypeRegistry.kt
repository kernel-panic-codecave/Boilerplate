package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.FilterHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState
import net.kernelpanicsoft.tubularstorage.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.InterfaceHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.FilterHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.SyncHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.SyncHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookType
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation

/** Registers Tubular Storage's [PipeHookType]s into the custom registry [Registrars] declares. */
@Suppress("UNCHECKED_CAST")
object HookTypeRegistry : ADeferredRegistryHolder<PipeHookType<out HookHolderState>>(
	TubularStorage.MOD,
	Registrars.HOOK_TYPE.key() as ResourceKey<Registry<PipeHookType<out HookHolderState>>>,
) {
	val Extraction: PipeHookType<ExtractionHookState> by register(ExtractionHookType.ID) { ExtractionHookType }
	val Filter: PipeHookType<FilterHookState> by register(FilterHookType.ID) { FilterHookType }
	val Provider: PipeHookType<ProviderHookState> by register(ProviderHookType.ID) { ProviderHookType }
	val Sync: PipeHookType<SyncHookState> by register(SyncHookType.ID) { SyncHookType }
	val Requester: PipeHookType<RequesterHookState> by register(RequesterHookType.ID) { RequesterHookType }
	val Terminal: PipeHookType<TerminalHookState> by register(TerminalHookType.ID) { TerminalHookType }
	val Interface: PipeHookType<InterfaceHookState> by register(InterfaceHookType.ID) { InterfaceHookType }
	val PatternProvider: PipeHookType<PatternProviderHookState> by register(PatternProviderHookType.ID) { PatternProviderHookType }

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
	fun byId(id: ResourceLocation): PipeHookType<HookHolderState>? = Registrars.HOOK_TYPE.get(id) as? PipeHookType<HookHolderState>
}
