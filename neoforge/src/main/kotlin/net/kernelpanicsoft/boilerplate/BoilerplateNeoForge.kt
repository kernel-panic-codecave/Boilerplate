package net.kernelpanicsoft.boilerplate

import dev.nyon.klf.MOD_BUS
import net.kernelpanicsoft.boilerplate.client.NeoForgeDebugRendering
import net.kernelpanicsoft.boilerplate.power.NeoForgePressureLookup
import net.kernelpanicsoft.boilerplate.power.PressureApi
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent

/**
 * NeoForge entrypoint for the mod, registered via the `@Mod` annotation.
 *
 * Delegates all real initialization to [Boilerplate], wiring its lifecycle calls into the
 * NeoForge mod-bus events ([FMLConstructModEvent], [FMLClientSetupEvent], [FMLCommonSetupEvent]).
 */
@Mod(Boilerplate.MOD_ID)
object BoilerplateNeoForge {
	init {
		PressureApi.init(NeoForgePressureLookup)

		MOD_BUS.addListener<FMLConstructModEvent> {
			Boilerplate.init()
		}
		MOD_BUS.addListener<FMLClientSetupEvent> {
			Boilerplate.initClient()
			NeoForgeDebugRendering.register()
		}
		MOD_BUS.addListener<FMLCommonSetupEvent> {
			Boilerplate.initCommon()
		}
		MOD_BUS.addListener<RegisterCapabilitiesEvent> { event ->
			NeoForgePressureLookup.registerCapabilities(event)
		}
	}
}
