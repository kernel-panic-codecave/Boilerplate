package net.kernelpanicsoft.tubularstorage

import dev.nyon.klf.MOD_BUS
import net.kernelpanicsoft.tubularstorage.power.NeoForgePressureLookup
import net.kernelpanicsoft.tubularstorage.power.PressureApi
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent

/**
 * NeoForge entrypoint for the mod, registered via the `@Mod` annotation.
 *
 * Delegates all real initialization to [TubularStorage], wiring its lifecycle calls into the
 * NeoForge mod-bus events ([FMLConstructModEvent], [FMLClientSetupEvent], [FMLCommonSetupEvent]).
 */
@Mod(TubularStorage.MOD_ID)
object TubularStorageNeoForge {
	init {
		PressureApi.init(NeoForgePressureLookup)

		MOD_BUS.addListener<FMLConstructModEvent> {
			TubularStorage.init()
		}
		MOD_BUS.addListener<FMLClientSetupEvent> {
			TubularStorage.initClient()
		}
		MOD_BUS.addListener<FMLCommonSetupEvent> {
			TubularStorage.initCommon()
		}
		MOD_BUS.addListener<RegisterCapabilitiesEvent> { event ->
			NeoForgePressureLookup.registerCapabilities(event)
		}
	}
}
