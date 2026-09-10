package net.kernelpanicsoft.boilerplate.registry

import net.kernelpanicsoft.archie.registries.RegistrarHelper
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.resource.ResourceKind
import net.kernelpanicsoft.boilerplate.pipe.encasement.EncasementHolderState
import net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType
import net.kernelpanicsoft.boilerplate.pipe.hook.HookHolderState
import net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterConditionState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterConditionType
import net.kernelpanicsoft.boilerplate.pipe.network.NetworkType

/**
 * Declares Boilerplate's custom `pipe_hook_type`/`pipe_encasement_type`/
 * `filter_condition_type`/`network_type` registries - real Minecraft [Registrar]s, synced to
 * clients, so a [PipeHookType]/[PipeEncasementType]/[FilterConditionType]/[NetworkType] is a
 * genuine registry entry rather than a hardcoded enum. Populating them with entries is
 * [HookTypeRegistry]/[EncasementTypeRegistry]/[net.kernelpanicsoft.boilerplate.registry.FilterConditionTypeRegistry]/[NetworkTypeRegistry]'s
 * job; this only declares the registries themselves. Must be [init]ialized before any of them.
 */
object Registrars : RegistrarHelper(Boilerplate.MOD_ID) {
	val HOOK_TYPE by registry<PipeHookType<out HookHolderState>>("pipe_hook_type") { syncToClients() }
	val ENCASEMENT_TYPE by registry<PipeEncasementType<out EncasementHolderState>>("pipe_encasement_type") { syncToClients() }
	val FILTER_CONDITION_TYPE by registry<FilterConditionType<out FilterConditionState>>("filter_condition_type") { syncToClients() }
	val NETWORK_TYPE by registry<NetworkType>("network_type") { syncToClients() }

	val RESOURCE_KIND by registry<ResourceKind>("resource_kind") { syncToClients() }
}
