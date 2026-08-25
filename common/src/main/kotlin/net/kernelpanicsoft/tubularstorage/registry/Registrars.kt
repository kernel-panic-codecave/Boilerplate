package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.registries.RegistrarHelper
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.encasement.EncasementHolderState
import net.kernelpanicsoft.tubularstorage.pipe.encasement.PipeEncasementType
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState
import net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterConditionState
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterConditionType

/**
 * Declares Tubular Storage's custom `pipe_hook_type`/`pipe_encasement_type`/
 * `filter_condition_type` registries - real Minecraft [Registrar]s, synced to clients, so a
 * [PipeHookType]/[PipeEncasementType]/[FilterConditionType] is a genuine registry entry rather
 * than a hardcoded enum. Populating them with entries is
 * [HookTypeRegistry]/[EncasementTypeRegistry]/[net.kernelpanicsoft.tubularstorage.registry.FilterConditionTypeRegistry]'s
 * job; this only declares the registries themselves. Must be [init]ialized before any of them.
 */
object Registrars : RegistrarHelper(TubularStorage.MOD_ID) {
	val HOOK_TYPE by registry<PipeHookType<out HookHolderState>>("pipe_hook_type") { syncToClients() }
	val ENCASEMENT_TYPE by registry<PipeEncasementType<out EncasementHolderState>>("pipe_encasement_type") { syncToClients() }
	val FILTER_CONDITION_TYPE by registry<FilterConditionType<out FilterConditionState>>("filter_condition_type") { syncToClients() }
}
