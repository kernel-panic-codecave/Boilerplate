package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.registries.RegistrarHelper
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState
import net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterConditionState
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterConditionType

/**
 * Declares Tubular Storage's custom `pipe_hook_type`/`filter_condition_type` registries - real
 * Minecraft [Registrar]s, synced to clients, so a [PipeHookType]/[FilterConditionType] is a
 * genuine registry entry rather than a hardcoded enum. Populating them with entries is
 * [HookTypeRegistry]/[net.kernelpanicsoft.tubularstorage.registry.FilterConditionTypeRegistry]'s
 * job; this only declares the registries themselves. Must be [init]ialized before either.
 */
object Registrars : RegistrarHelper(TubularStorage.MOD_ID) {
	val HOOK_TYPE by registry<PipeHookType<out HookHolderState>>("pipe_hook_type") { syncToClients() }
	val FILTER_CONDITION_TYPE by registry<FilterConditionType<out FilterConditionState>>("filter_condition_type") { syncToClients() }
}
