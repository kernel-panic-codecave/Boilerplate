package net.kernelpanicsoft.boilerplate

import dev.architectury.platform.Platform
import dev.nyon.klf.MOD_BUS
import net.kernelpanicsoft.boilerplate.client.NeoForgeDebugCommands
import net.kernelpanicsoft.boilerplate.client.NeoForgeDebugRendering
import net.kernelpanicsoft.boilerplate.compat.jei.registerNeoForgeJeiResourceStacks
import net.kernelpanicsoft.boilerplate.compat.mekanism.*
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent

/**
 * NeoForge entrypoint for the mod, registered via the `@Mod` annotation.
 *
 * Delegates all real initialization to [Boilerplate], wiring its lifecycle calls into the
 * NeoForge mod-bus events ([FMLConstructModEvent], [FMLClientSetupEvent], [FMLCommonSetupEvent]),
 * plus the Mekanism bridge, which is the one thing here that is genuinely loader-specific rather
 * than merely wired up per loader.
 */
@Mod(Boilerplate.MOD_ID)
object BoilerplateNeoForge {
	init {
		MOD_BUS.addListener<FMLConstructModEvent> {
			Boilerplate.init()
			if (Platform.isModLoaded("mekanism")) {
				ChemicalResourceKindRegistry.init()
				ChemicalNetworkTypeRegistry.init()
			}
		}
		MOD_BUS.addListener<FMLClientSetupEvent> {
			Boilerplate.initClient()
			NeoForgeDebugRendering.register()
			NeoForgeDebugCommands.register()
			// JEI's fluid ingredient type can only be named from here - see JeiResourceStacks.
			// Guarded on JEI actually being present, since naming it at all loads its classes.
			if (Platform.isModLoaded("jei")) registerNeoForgeJeiResourceStacks()
			if (Platform.isModLoaded("mekanism")) registerMekanismResourceStacks()
		}
		MOD_BUS.addListener<FMLCommonSetupEvent> {
			Boilerplate.initCommon()
		}
	}
}
