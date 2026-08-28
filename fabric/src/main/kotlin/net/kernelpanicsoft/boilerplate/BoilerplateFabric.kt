package net.kernelpanicsoft.boilerplate

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.api.ModInitializer
import net.kernelpanicsoft.boilerplate.power.FabricPressureLookup
import net.kernelpanicsoft.boilerplate.power.PressureApi

/**
 * Fabric entrypoint for the mod (`fabric.mod.json` `main`/`client` entrypoints).
 *
 * Delegates all real initialization to [Boilerplate]; this object only wires that shared logic
 * into Fabric's initializer callbacks.
 */
object BoilerplateFabric : ModInitializer, ClientModInitializer {
	override fun onInitialize() {
		PressureApi.init(FabricPressureLookup)
		Boilerplate.init()
		Boilerplate.initCommon()
	}

	override fun onInitializeClient() {
		Boilerplate.initClient()
	}
}
