package net.kernelpanicsoft.boilerplate

import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.platform.Mod
import dev.architectury.platform.Platform
import net.kernelpanicsoft.archie.data.platform.ADataGeneratorPlatform
import net.kernelpanicsoft.archie.events.datagen.ADatagenEvents
import net.kernelpanicsoft.archie.events.gametest.AGametestEvents
import net.kernelpanicsoft.archie.gametest.platform.AGameTestPlatform
import net.kernelpanicsoft.archie.registries.CustomModelRegistry
import net.kernelpanicsoft.archie.util.withMinecraftClient
import net.kernelpanicsoft.boilerplate.datagen.BoilerplateDatagen
import net.kernelpanicsoft.boilerplate.debug.DebugOverlayViewers
import net.kernelpanicsoft.boilerplate.debug.ResourceTrace
import net.kernelpanicsoft.boilerplate.gametest.BoilerplateGameTest
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.DebugNetworkSync
import net.kernelpanicsoft.boilerplate.network.WarehouseDebugSync
import net.kernelpanicsoft.boilerplate.pipe.network.PipeNetworkManager
import net.kernelpanicsoft.boilerplate.power.network.PressurePipeNetworkManager
import net.kernelpanicsoft.boilerplate.registry.*
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseBlockEventListener
import net.kernelpanicsoft.boilerplate.warehouse.client.GantrySounds
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

		// Before anything that might read a config value. Registers the specs and their load
		// timings - the server one lands per-world at server start and is pushed to joining
		// clients, the client one at client setup - and, where Cloth Config is present, the
		// settings screen for both.
		BoilerplateConfig.init()

		Registrars.init()
		TagsRegistry.init()
		NetworkTypeRegistry.init()
		ResourceKindRegistry.init()
		HookTypeRegistry.init()
		EncasementTypeRegistry.init()
		FilterConditionTypeRegistry.init()
		CreativeTabRegistry.init()
		BlockRegistry.init()
		ItemRegistry.init()
		SoundRegistry.init()
		TileRegistry.init()
		GuiRegistry.init()
		LootRegistry.init()

		// Before anything can start a resource reload, which is why this is here and not in
		// [initClient]. Flywheel registers a PartialModel for every one that *exists* when
		// `ModelEvent.RegisterAdditional` fires, and populates each from the baked map afterwards -
		// so a PartialModel constructed between those two events is in Flywheel's map, was never
		// registered for baking, and ends up with a null baked model. Client setup is close enough to
		// the initial reload for that to be a race, and touching this class's own model location here
		// - at mod construction, seconds earlier - takes it out of the running entirely. Reaching the
		// [WarehouseControllerVisual] companion at all is what constructs its PartialModel.
		//
		// [withMinecraftClient], not [onClient]: a data run is the client distribution with no game
		// behind it and has no models to bake.
		withMinecraftClient { CustomModelRegistry.register(MOD, WarehouseControllerVisual.HEAD_MODEL_RL) }

		WarehouseBlockEventListener.register()

		BoilerplateNetworkChannel.init()

		TickEvent.SERVER_LEVEL_POST.register { level -> PipeNetworkManager.get(level).tick(level) }
		TickEvent.SERVER_LEVEL_POST.register { level -> PressurePipeNetworkManager.get(level).tick(level) }
		TickEvent.SERVER_LEVEL_POST.register { level -> DebugNetworkSync.tickLevel(level) }
		TickEvent.SERVER_LEVEL_POST.register { level -> WarehouseDebugSync.tickLevel(level) }
		// Per server tick, not per level: a trace line names a position but the sequence is one
		// story across every dimension, and splitting the flush per level would interleave it.
		TickEvent.SERVER_POST.register { server -> ResourceTrace.flushToViewers(server) }
		PlayerEvent.PLAYER_QUIT.register { player -> DebugOverlayViewers.setViewer(player.uuid, flags = emptySet()) }

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
		requireFlywheel()
		GantrySounds.register()
	}

	/**
	 * Fails fast, on the client, if Flywheel is missing.
	 */
	private fun requireFlywheel() {
		if (Platform.isModLoaded("flywheel")) return
		throw IllegalStateException(
			"Boilerplate requires Flywheel on the client - everything it draws is rendered through it, " +
				"with no vanilla block entity renderers to fall back on. Install Flywheel and restart.",
		)
	}

	/** Reserved for common-side initialization that must run after both [init] and platform bootstrap. */
	@JvmStatic
	fun initCommon() {
	}
}
