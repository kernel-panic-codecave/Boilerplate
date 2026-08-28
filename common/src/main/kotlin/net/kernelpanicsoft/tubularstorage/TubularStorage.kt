package net.kernelpanicsoft.tubularstorage

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.TickEvent
import dev.architectury.platform.Mod
import dev.architectury.platform.Platform
import net.kernelpanicsoft.archie.data.platform.ADataGeneratorPlatform
import net.kernelpanicsoft.archie.events.datagen.ADatagenEvents
import net.kernelpanicsoft.archie.events.gametest.AGametestEvents
import net.kernelpanicsoft.archie.gametest.platform.AGameTestPlatform
import net.kernelpanicsoft.archie.registries.CustomModelRegistry
import net.kernelpanicsoft.tubularstorage.datagen.TubularStorageDatagen
import net.kernelpanicsoft.tubularstorage.gametest.TubularStorageGameTest
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.pipe.network.PipeNetworkManager
import net.kernelpanicsoft.tubularstorage.power.network.PressurePipeNetworkManager
import net.kernelpanicsoft.tubularstorage.registry.*
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseBlockEventListener
import net.kernelpanicsoft.tubularstorage.warehouse.client.WarehouseControllerVisual
import org.slf4j.Logger

/**
 * Tubular Storage's mod object and library entrypoint.
 */
object TubularStorage {
	/** Tubular Storage's own mod id, used as the namespace for its resources and network channel. */
	const val MOD_ID = "tubularstorage"

	/** The Architectury [Mod] descriptor for Tubular Storage itself. */
	@JvmField
	val MOD: Mod = Platform.getMod(MOD_ID)

	/** Shared SLF4J logger for Tubular Storage's own internal logging. */
	@JvmField
	val LOGGER: Logger = LogUtils.getLogger()

	/**
	 * Initializes Tubular Storage's shared (loader-independent) systems.
	 *
	 * Also registers Tubular Storage's GameTest suite here, not from [initCommon] - Archie's own
	 * gametest registry closes for registration by the time each loader's `FMLCommonSetupEvent`/
	 * equivalent fires, so a mod registering its suite that late loses the race and never gets its
	 * tests picked up. `FMLConstructModEvent` (what [init] runs from) is early enough. Only actually
	 * touches gametest types when launched via `runGametest`/`runGametestClient` - see
	 * [TubularStorageGameTest]'s KDoc for why this check matters beyond just "don't waste time
	 * registering tests nobody's running": every reference to an `archie-gametest-common` type,
	 * including [AGametestEvents]'s own `+=`, must stay inside this guard, since that dependency is
	 * absent from the production runtime classpath. [TubularStorageDatagen]'s `archie-datagen-common`
	 * dependency behind [ADataGeneratorPlatform.isDataGen] is the same story, for `runDatagen`.
	 */
	@JvmStatic
	fun init() {
		LOGGER.info("Tubular Storage initializing")

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

		TubularStorageNetworkChannel.init()

		TickEvent.SERVER_LEVEL_POST.register { level -> PipeNetworkManager.get(level).tick(level) }
		TickEvent.SERVER_LEVEL_POST.register { level -> PressurePipeNetworkManager.get(level).tick(level) }

		if (AGameTestPlatform.isGameTest) {
			AGametestEvents += MOD
			TubularStorageGameTest.init()
		}

		if (ADataGeneratorPlatform.isDataGen) {
			ADatagenEvents += MOD
			TubularStorageDatagen.init()
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
