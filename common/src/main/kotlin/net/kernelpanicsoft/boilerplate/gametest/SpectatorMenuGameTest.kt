package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.MenuProvider
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock

/**
 * Every block whose menu is an Architectury *extended* menu must refuse vanilla's spectator
 * open path.
 *
 * [net.minecraft.server.level.ServerPlayerGameMode.useItemOn] opens
 * `state.getMenuProvider(...)` directly for a spectator, before any normal use handling - that is
 * how a spectator peeks into a chest. It uses the **plain** `openMenu`, so no extra data is
 * written, and a menu whose client constructor reads its block position out of a
 * `FriendlyByteBuf` gets `null` and takes the client's packet handler down with
 * `Cannot invoke "FriendlyByteBuf.readBlockPos()" because "buf" is null`.
 *
 * Asserted over the *whole* block registry rather than a hand-written list, because the failure
 * mode is silent until someone spectates: a new menu-bearing block that forgets the override
 * inherits [net.minecraft.world.level.block.BaseEntityBlock]'s default - which hands the block
 * entity straight over - and nothing else notices.
 */
@Suppress("unused")
class SpectatorMenuGameTest {
	@GameTest(template = SMALL)
	fun GameTestHelper.testNoMenuBlockIsOpenableBySpectators() {
		val pos = BlockPos(1, 2, 1)
		val offenders = mutableListOf<String>()

		for (block in BuiltInRegistries.BLOCK) {
			val id = BuiltInRegistries.BLOCK.getKey(block)
			if (id.namespace != "boilerplate") continue
			if (block !is EntityBlock) continue

			setBlock(pos, block.defaultBlockState())
			val absolute = absolutePos(pos)
			// Only meaningful for a block whose entity actually is a MenuProvider - anything else
			// already returns null and has nothing to opt out of.
			val entity = level.getBlockEntity(absolute)
            if (entity !is MenuProvider) continue

			if (level.getBlockState(absolute).getMenuProvider(level, absolute) != null) offenders += id.toString()
		}
		setBlock(pos, Blocks_AIR)

		assertTrue(offenders.isEmpty()) {
			"These blocks still hand vanilla's spectator path a MenuProvider, so spectating one " +
				"crashes the client on a null packet buffer: $offenders"
		}
		succeed()
	}

	private companion object {
		val Blocks_AIR: net.minecraft.world.level.block.state.BlockState = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
	}
}
