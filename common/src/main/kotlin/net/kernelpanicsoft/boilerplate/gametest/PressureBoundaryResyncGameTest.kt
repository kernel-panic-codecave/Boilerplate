package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.power.PressureLine
import net.kernelpanicsoft.boilerplate.power.PressureTankEncasementType
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * GameTest coverage for [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock]'s own
 * `resyncNetworkMembership` - previously, an Adapter hook attached *after* a dedicated Pressure Pipe
 * run and an item-pipe network had already each been registered as their own separate
 * [net.kernelpanicsoft.boilerplate.power.network.PressurePipeNetworkManager] network (which
 * happens on the very first tick either side of the junction exists at all, long before a player
 * gets around to placing an adapter) never actually bridged them:
 * [net.kernelpanicsoft.boilerplate.pipe.network.AbstractPipeNetworkManager.ensureRegistered] is
 * idempotent - once a position is registered, it never re-examines its own edges again on its own -
 * so a real, full tank sitting right there stayed permanently unreachable to anything on the item-
 * pipe side, no matter how long you waited. `MultipartBlock.clickBlockWithItem`/`detachWithWrench`
 * now force both network managers to re-evaluate the clicked position's membership on hook attach/
 * detach, exactly the same eviction [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock.removeJustThePipe]
 * already did for a pipe-type change.
 */
@Suppress("unused")
class PressureBoundaryResyncGameTest {
	@GameTest(template = SMALL, timeoutTicks = 60)
	fun GameTestHelper.testAttachingAnAdapterAfterBothSidesAreAlreadyRegisteredStillBridgesThem() {
		val tankPos = BlockPos(0, 2, 0)
		val bridgePos = BlockPos(0, 2, 1)
		val itemPos = BlockPos(0, 2, 2)

		setBlock(tankPos, BlockRegistry.Multipart.defaultBlockState())
		val tank = getBlockEntity(tankPos) as MultipartBlockEntity
		tank.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.PressurePipe)
		val tankState = PressureTankEncasementType.createState()
		tank.encasement.value = tankState
		tankState.pressure.insert(10_000, false)

		setBlock(bridgePos, BlockRegistry.PressurePipe.defaultBlockState())

		setBlock(itemPos, BlockRegistry.Multipart.defaultBlockState())
		val itemTile = getBlockEntity(itemPos) as MultipartBlockEntity
		itemTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		// Deliberately no Adapter yet - the item-pipe/dedicated-pressure-pipe junction at
		// bridgePos/itemPos is an unbridged PressureNetworkBoundary until one's attached below.

		runAfterDelay(10) {
			val serverLevel = level as ServerLevel
			val beforeAdapter = PressureLine.find(serverLevel, absolutePos(itemPos))
			assertTrue(beforeAdapter == null) {
				"Expected the item-pipe side to have no reachable pressure line yet, with no Adapter bridging the junction - got $beforeAdapter"
			}

			val player = makeMockPlayer(GameType.CREATIVE)
			player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(ItemRegistry.AdapterHook))
			val itemAbsPos = absolutePos(itemPos)
			val hitResult = BlockHitResult(Vec3.atCenterOf(itemAbsPos), Direction.NORTH, itemAbsPos, false)
			BlockRegistry.Multipart.clickBlockWithItem(
				player.mainHandItem, serverLevel.getBlockState(itemAbsPos), serverLevel, itemAbsPos, player, InteractionHand.MAIN_HAND, hitResult,
			)

			runAfterDelay(10) {
				val afterAdapter = PressureLine.find(level as ServerLevel, absolutePos(itemPos))
				assertTrue(afterAdapter != null) {
					"Expected the Adapter hook, attached after the fact, to bridge the junction and make the tank reachable from the item-pipe side"
				}
				succeed()
			}
		}
	}
}
