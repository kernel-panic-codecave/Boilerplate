package net.kernelpanicsoft.tubularstorage

import dev.nyon.klf.MOD_BUS
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent

/**
 * NeoForge entrypoint for the mod, registered via the `@Mod` annotation.
 *
 * Delegates all real initialization to [TubularStorage], wiring its lifecycle calls into the
 * NeoForge mod-bus events ([FMLConstructModEvent], [FMLClientSetupEvent], [FMLCommonSetupEvent]).
 */
@Mod(TubularStorage.MOD_ID)
object TubularStorageNeoForge {
	init {
		MOD_BUS.addListener<FMLConstructModEvent> {
			TubularStorage.init()
		}
		MOD_BUS.addListener<FMLClientSetupEvent> {
			TubularStorage.initClient()
		}
		MOD_BUS.addListener<FMLCommonSetupEvent> {
			TubularStorage.initCommon()
		}
	}
}
