package net.kernelpanicsoft.tubularstorage.gametest

import net.kernelpanicsoft.archie.events.gametest.AGametestEvents
import net.kernelpanicsoft.archie.gametest.AGameTestEventObject
import net.kernelpanicsoft.tubularstorage.TubularStorage

/**
 * ID of a 5x4x5 all-air structure template, for GameTests that place real blocks and need actual
 * room - Archie's own `EMPTY` (`archie:gametest/empty`) is a 1x1x1 envelope meant only for tests
 * that exercise server-side code directly and never call `setBlock`.
 */
internal const val SMALL = "tubularstorage:gametest/small"

/**
 * Registers Tubular Storage's GameTest suite. Only ever touched from behind
 * [net.kernelpanicsoft.archie.gametest.platform.AGameTestPlatform.isGameTest] - see
 * [TubularStorage.initCommon] - so `archie-gametest-common`, a dev-only dependency absent from
 * the production runtime classpath, is never resolved outside of a `runGametest`/`runGametestClient`
 * launch.
 */
internal object TubularStorageGameTest : AGameTestEventObject(TubularStorage.MOD) {
	override fun AGametestEvents.ArchieGameTestBuilder.handler() = tubularStorageGameTests()
}

private fun AGametestEvents.ArchieGameTestBuilder.tubularStorageGameTests() {
	server {
		register<PipeNetworkGameTest>()
		register<PipeExtractionGameTest>()
		register<WarehouseGameTest>()
		register<RackGameTest>()
		register<SubnetBoundaryGameTest>()
		register<AssemblyTableGameTest>()
		register<CraftingResolverGameTest>()
		register<TerminalCraftGameTest>()
	}
}
