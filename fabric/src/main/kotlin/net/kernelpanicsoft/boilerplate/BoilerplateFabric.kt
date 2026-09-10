package net.kernelpanicsoft.boilerplate

import dev.architectury.platform.Platform
import net.fabricmc.api.ClientModInitializer
import net.kernelpanicsoft.boilerplate.compat.jei.registerFabricJeiResourceStacks
import net.fabricmc.api.ModInitializer
import net.kernelpanicsoft.boilerplate.client.FabricDebugCommands
import net.kernelpanicsoft.boilerplate.client.FabricDebugRendering

/**
 * Fabric entrypoint for the mod (`fabric.mod.json` `main`/`client` entrypoints).
 *
 * Delegates all real initialization to [Boilerplate]; this object only wires that shared logic
 * into Fabric's initializer callbacks.
 */
object BoilerplateFabric : ModInitializer, ClientModInitializer {
	override fun onInitialize() {
		Boilerplate.init()
		Boilerplate.initCommon()
	}

	override fun onInitializeClient() {
		Boilerplate.initClient()
		FabricDebugRendering.register()
		FabricDebugCommands.register()
		// JEI's fluid ingredient type can only be named from here - see JeiResourceStacks. Guarded
		// on JEI actually being present, since naming it at all loads its classes.
		if (Platform.isModLoaded("jei")) registerFabricJeiResourceStacks()
	}
}
