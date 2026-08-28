package net.kernelpanicsoft.tubularstorage

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.api.ModInitializer
import net.kernelpanicsoft.tubularstorage.power.FabricPressureLookup
import net.kernelpanicsoft.tubularstorage.power.PressureApi

/**
 * Fabric entrypoint for the mod (`fabric.mod.json` `main`/`client` entrypoints).
 *
 * Delegates all real initialization to [TubularStorage]; this object only wires that shared logic
 * into Fabric's initializer callbacks.
 */
object TubularStorageFabric : ModInitializer, ClientModInitializer {
	override fun onInitialize() {
		PressureApi.init(FabricPressureLookup)
		TubularStorage.init()
		TubularStorage.initCommon()
	}

	override fun onInitializeClient() {
		TubularStorage.initClient()
	}
}
