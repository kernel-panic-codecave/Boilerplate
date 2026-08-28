package net.kernelpanicsoft.boilerplate

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.TickEvent
import dev.architectury.platform.Mod
import dev.architectury.platform.Platform
import net.kernelpanicsoft.archie.data.platform.ADataGeneratorPlatform
import net.kernelpanicsoft.archie.events.datagen.ADatagenEvents
import net.kernelpanicsoft.archie.events.gametest.AGametestEvents
import net.kernelpanicsoft.archie.gametest.platform.AGameTestPlatform
import net.kernelpanicsoft.archie.registries.CustomModelRegistry
import net.kernelpanicsoft.boilerplate.datagen.BoilerplateDatagen
import net.kernelpanicsoft.boilerplate.gametest.BoilerplateGameTest
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.pipe.network.PipeNetworkManager
import net.kernelpanicsoft.boilerplate.power.network.PressurePipeNetworkManager
import net.kernelpanicsoft.boilerplate.registry.*
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseBlockEventListener
import net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseControllerVisual
import org.slf4j.Logger

/**
 * Boilerplate's mod object and library entrypoint.
 */
object Boilerplate {
	/** Boilerplate's own mod id, used as the namespace for its resources and network channel. */
	const val MOD_ID = "boilerplate"

	/** The Architectury [Mod] descriptor for Boilerplate itself. */
	@JvmField
	val MOD: Mod = Platform.getMod(MOD_ID)

	/** Shared SLF4J logger for Boilerplate's own internal logging. */
	@JvmField
	val LOGGER: Logger = LogUtils.getLogger()

	/**
	 * Initializes Boilerplate's shared (loader-independent) systems.
	 *
	 * Also registers Boilerplate's GameTest suite here, not from [initCommon] - Archie's own
	 * gametest registry closes for registration by the time each loader's `FMLCommonSetupEvent`/
	 * equivalent fires, so a mod registering its suite that late loses the race and never gets its
	 * tests picked up. `FMLConstructModEvent` (what [init] runs from) is early enough. Only actually
	 * touches gametest types when launched via `runGametest`/`runGametestClient` - see
	 * [BoilerplateGameTest]'s KDoc for why this check matters beyond just "don't waste time
	 * registering tests nobody's running": every reference to an `archie-gametest-common` type,
	 * including [AGametestEvents]'s own `+=`, must stay inside this guard, since that dependency is
	 * absent from the production runtime classpath. [BoilerplateDatagen]'s `archie-datagen-common`
	 * dependency behind [ADataGeneratorPlatform.isDataGen] is the same story, for `runDatagen`.
	 */
	@JvmStatic
	fun init() {
		LOGGER.info("Boilerplate initializing")

		Registrars.init()
		TagsRegistry.init()
		NetworkTypeRegistry.init()
		HookTypeRegistry.init()
		EncasementTypeRegistry.init()
		FilterConditionTypeRegistry.init()
		BlockRegistry.init()
		ItemRegistry.init()
		TileRegistry.init()
		GuiRegistry.init()
		LootRegistry.init()

		WarehouseBlockEventListener.register()

		BoilerplateNetworkChannel.init()

		TickEvent.SERVER_LEVEL_POST.register { level -> PipeNetworkManager.get(level).tick(level) }
		TickEvent.SERVER_LEVEL_POST.register { level -> PressurePipeNetworkManager.get(level).tick(level) }

		if (AGameTestPlatform.isGameTest) {
			AGametestEvents += MOD
			BoilerplateGameTest.init()
		}

		if (ADataGeneratorPlatform.isDataGen) {
			ADatagenEvents += MOD
			BoilerplateDatagen.init()
		}
	}

	/**
	 * Reserved for client-only initialization that must run after [init], from a client
	 * entrypoint.
	 */
	@JvmStatic
	fun initClient() {
		CustomModelRegistry.register(MOD, WarehouseControllerVisual.HEAD_MODEL_RL)
	}

	/** Reserved for common-side initialization that must run after both [init] and platform bootstrap. */
	@JvmStatic
	fun initCommon() {
	}
}
