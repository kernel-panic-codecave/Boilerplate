package net.kernelpanicsoft.tubularstorage

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin
import net.kernelpanicsoft.tubularstorage.warehouse.client.WarehouseControllerVisual

/**
 * Fabric entrypoint for the mod (`fabric.mod.json` `main`/`client` entrypoints).
 *
 * Delegates all real initialization to [TubularStorage]; this object only wires that shared logic
 * into Fabric's initializer callbacks.
 */
object TubularStorageFabric : ModInitializer, ClientModInitializer {
	override fun onInitialize() {
		TubularStorage.init()
		TubularStorage.initCommon()
	}

	override fun onInitializeClient() {
		TubularStorage.initClient()
		ModelLoadingPlugin.register { pluginContext ->
			pluginContext.addModels(WarehouseControllerVisual.HEAD_MODEL_RL)
		}
	}
}
