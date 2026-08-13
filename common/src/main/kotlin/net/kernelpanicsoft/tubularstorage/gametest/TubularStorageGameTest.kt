package net.kernelpanicsoft.tubularstorage.gametest

import net.kernelpanicsoft.archie.events.gametest.AGametestEvents
import net.kernelpanicsoft.archie.gametest.AGameTestEventObject
import net.kernelpanicsoft.tubularstorage.TubularStorage

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
	}
}
