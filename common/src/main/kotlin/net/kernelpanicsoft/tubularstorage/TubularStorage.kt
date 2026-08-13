package net.kernelpanicsoft.tubularstorage

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.TickEvent
import dev.architectury.platform.Mod
import dev.architectury.platform.Platform
import net.kernelpanicsoft.archie.events.gametest.AGametestEvents
import net.kernelpanicsoft.archie.gametest.platform.AGameTestPlatform
import net.kernelpanicsoft.archie.serialization.SerializationManager
import net.kernelpanicsoft.tubularstorage.gametest.TubularStorageGameTest
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.pipe.entity.DirectionSerializer
import net.kernelpanicsoft.tubularstorage.pipe.entity.DyeColorSerializer
import net.kernelpanicsoft.tubularstorage.pipe.network.PipeNetworkManager
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.Direction
import net.minecraft.world.item.DyeColor
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
	 */
	@JvmStatic
	fun init() {
		LOGGER.info("Tubular Storage initializing")
		AGametestEvents += MOD
		SerializationManager {
			module {
				contextual(Direction::class, DirectionSerializer)
				contextual(DyeColor::class, DyeColorSerializer)
			}
		}

		BlockRegistry.init()
		ItemRegistry.init()
		TileRegistry.init()
		GuiRegistry.init()

		TubularStorageNetworkChannel.init()

		TickEvent.SERVER_LEVEL_POST.register { level -> PipeNetworkManager.get(level).tick() }
	}

	/**
	 * Reserved for client-only initialization that must run after [init], from a client
	 * entrypoint.
	 */
	@JvmStatic
	fun initClient() {
	}

	/**
	 * Reserved for common-side initialization that must run after both [init] and platform
	 * bootstrap. Registers Tubular Storage's GameTest suite, but only when actually launched via
	 * `runGametest`/`runGametestClient` - see [TubularStorageGameTest]'s KDoc for why this check
	 * matters beyond just "don't waste time registering tests nobody's running".
	 */
	@JvmStatic
	fun initCommon() {
		if (AGameTestPlatform.isGameTest) {
			TubularStorageGameTest.init()
		}
	}
}
