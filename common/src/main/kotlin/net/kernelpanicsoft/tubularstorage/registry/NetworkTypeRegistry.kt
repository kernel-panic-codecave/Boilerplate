package net.kernelpanicsoft.tubularstorage.registry

import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.network.ItemNetworkType
import net.kernelpanicsoft.tubularstorage.pipe.network.NetworkType
import net.kernelpanicsoft.tubularstorage.power.network.PressureNetworkType
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation

/** Registers Tubular Storage's [NetworkType]s into the custom registry [Registrars] declares. */
@Suppress("UNCHECKED_CAST")
object NetworkTypeRegistry : ADeferredRegistryHolder<NetworkType>(
	TubularStorage.MOD,
	Registrars.NETWORK_TYPE.key() as ResourceKey<Registry<NetworkType>>,
) {
	val Item: NetworkType by register(ItemNetworkType.ID) { ItemNetworkType }
	val Pressure: NetworkType by register(PressureNetworkType.ID) { PressureNetworkType }

	/** Looks up a registered [NetworkType] by its full id - see [HookTypeRegistry.byId], which this mirrors exactly. */
	fun byId(id: ResourceLocation): NetworkType? = Registrars.NETWORK_TYPE.get(id)
}
