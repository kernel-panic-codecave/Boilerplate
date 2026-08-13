package net.kernelpanicsoft.tubularstorage

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.TickEvent
import dev.architectury.platform.Mod
import dev.architectury.platform.Platform
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.pipe.network.PipeNetworkManager
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
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
	 * bootstrap.
	 */
	@JvmStatic
	fun initCommon() {
	}
}
