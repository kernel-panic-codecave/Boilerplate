package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.registries.RegistrarHelper
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType

/**
 * Declares Tubular Storage's custom `pipe_hook_type` registry - a real Minecraft [Registrar],
 * synced to clients, so a [PipeHookType] is a genuine registry entry rather than a hardcoded enum.
 * Populating it with entries is [HookTypeRegistry]'s job; this only declares the registry itself.
 * Must be [init]ialized before [HookTypeRegistry].
 */
object HookTypeRegistrar : RegistrarHelper(TubularStorage.MOD_ID) {
	val HOOK_TYPE by registry<PipeHookType>("pipe_hook_type") { syncToClients() }
}
