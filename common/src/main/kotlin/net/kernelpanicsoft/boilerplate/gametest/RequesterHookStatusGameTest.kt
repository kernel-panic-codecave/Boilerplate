package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.amountHeld
import net.kernelpanicsoft.boilerplate.pipe.hook.requesterStatus
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.minecraft.world.level.block.Block

/**
 * What a requester hook's GUI reports about itself
 * ([net.kernelpanicsoft.boilerplate.pipe.gui.RequesterHookScreen]).
 *
 * The screen previously had no content at all - a blank window - so the hook's single configuration
 * slot was unreachable and there was no way to tell what it was doing. The readout matters most for
 * the case these tests centre on: a requester's role **flips** when it faces an
 * [InterfaceHookType] hook, keeping that interface stocked with its order instead of the inventory
 * next door. Nothing about the hook says so, so which of the two it is doing - and against what - is
 * otherwise invisible.
 *
 * Asserted against [requesterStatus] rather than through a real screen, since a gametest has no
 * client: that function is what the menu sends, and it lives beside the hook type so it walks the
 * same decision the hook itself walks each cycle.
 */
@Suppress("unused")
class RequesterHookStatusGameTest {

	/** Places a requester hook on [face] of a pipe segment at [pos], returning its state. */
	private fun GameTestHelper.placeRequester(pos: BlockPos, face: Direction): RequesterHookState {
		setBlock(pos, BlockRegistry.Multipart.defaultBlockState())
		val tile = getBlockEntity(pos) as MultipartBlockEntity
		tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val state = tile.hooks.getOrPut(face.name) { RequesterHookType.createState() } as RequesterHookState
		refreshConnections(pos)
		return state
	}

	/**
	 * Recomputes a segment's connection bits after `setBlock` skipped real placement logic - see
	 * [placeCraftingBuffer]'s own note. Only matters for tests that care about pipe *topology*
	 * (whether two segments are one subnet), which is exactly what the connection bits encode.
	 */
	private fun GameTestHelper.refreshConnections(pos: BlockPos) {
		val absolute = absolutePos(pos)
		level.setBlock(absolute, Block.updateFromNeighbourShapes(level.getBlockState(absolute), level, absolute), Block.UPDATE_ALL)
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAnUnconfiguredRequesterReportsNoOrder() {
		val hookPos = BlockPos(0, 2, 1)
		val state = placeRequester(hookPos, Direction.NORTH)
		setBlock(BlockPos(0, 2, 0), Blocks.CHEST.defaultBlockState())

		val status = requesterStatus(level as ServerLevel, absolutePos(hookPos), Direction.NORTH, state)

		assertTrue(!status.supplyingInterface) { "Expected an ordinary stocking requester, got $status" }
		assertTrue(status.held.isEmpty() && status.detail == "No targets set.") {
			"Expected an empty target row to report that plainly, got $status"
		}
		succeed()
	}

	/** With an order and a part-stocked neighbour, the readout is the same comparison the hook makes each cycle. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAStockingRequesterReportsItsProgress() {
		val hookPos = BlockPos(0, 2, 1)
		val chestPos = BlockPos(0, 2, 0)
		val state = placeRequester(hookPos, Direction.NORTH)
		setBlock(chestPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(chestPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 3))
		state.target(ItemResource.of(ItemStack(Items.DIAMOND)), 8)

		val status = requesterStatus(level as ServerLevel, absolutePos(hookPos), Direction.NORTH, state)

		assertTrue(!status.supplyingInterface) { "Expected an ordinary stocking requester, got $status" }
		assertTrue(status.held.firstOrNull() == 3L) {
			"Expected the readout to be the 3 the neighbour actually holds against this hook's own target of 8, got $status"
		}
		succeed()
	}

	/** No adjacent inventory at all is distinguished from an unset order - both leave the hook idle, for quite different reasons. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testARequesterFacingNothingSaysSo() {
		val hookPos = BlockPos(0, 2, 1)
		val state = placeRequester(hookPos, Direction.NORTH)
		state.target(ItemResource.of(ItemStack(Items.DIAMOND)), 8)

		val status = requesterStatus(level as ServerLevel, absolutePos(hookPos), Direction.NORTH, state)

		assertTrue(status.held.isEmpty() && status.detail == "Nothing to stock on this face.") {
			"Expected a requester facing air to say so rather than report false progress, got $status"
		}
		succeed()
	}

	/**
	 * Facing an interface hook, the requester reports that it is supplying it - and its progress is
	 * still measured against **its own** order, since that order is what the interface gets stocked
	 * with in this mode.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testARequesterFacingAnInterfaceReportsSupplyingIt() {
		val hookPos = BlockPos(0, 2, 1)
		val interfacePos = BlockPos(0, 2, 0)
		val state = placeRequester(hookPos, Direction.NORTH)

		setBlock(interfacePos, BlockRegistry.Multipart.defaultBlockState())
		val interfaceTile = getBlockEntity(interfacePos) as MultipartBlockEntity
		interfaceTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.SOUTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		// A ghost row of its own, naming something else entirely - the requester's order is what
		// governs here, so this must not show up in the readout at all.
		interfaceState.target(ItemResource.of(ItemStack(Items.GOLD_INGOT)), 32)
		interfaceState.stock.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 4, false)

		state.target(ItemResource.of(ItemStack(Items.DIAMOND)), 10)

		val status = requesterStatus(level as ServerLevel, absolutePos(hookPos), Direction.NORTH, state)

		assertTrue(status.supplyingInterface) {
			"Expected a requester facing an interface to report that it supplies it, got $status"
		}
		assertTrue(status.held.firstOrNull() == 4L) {
			"Expected the 4 diamonds the interface actually holds against this hook's own target, not anything about its unrelated 32 gold target, got $status"
		}
		succeed()
	}

	/**
	 * A requester facing an interface with **nothing behind it** parks nothing on the seam.
	 *
	 * This used to assert the opposite - that the interface settled holding exactly the requester's
	 * order - because stocking the interface *was* the behaviour. It is not any more: an interface is
	 * a junction, and a row saying "keep 6 diamonds" means "keep the things behind here supplied",
	 * not "pile 6 diamonds on the boundary" (see
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType]). With no destination on the far
	 * side, the honest amount to send is none, and the requester's own network keeps its diamonds.
	 *
	 * `SubnetBoundaryGameTest.testARequesterStocksTheFarSubnetsDestinationsNotTheSeam` is the positive
	 * half - two chests behind the interface, both stocked, the seam left empty.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testARequesterFacingAnEmptySubnetParksNothingOnTheSeam() {
		val interfacePos = BlockPos(0, 2, 0)
		val requesterPos = BlockPos(0, 2, 1)
		val sourcePos = BlockPos(1, 2, 1)

		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 32))

		setBlock(interfacePos, BlockRegistry.Multipart.defaultBlockState())
		val interfaceTile = getBlockEntity(interfacePos) as MultipartBlockEntity
		interfaceTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.SOUTH.name) { InterfaceHookType.createState() } as InterfaceHookState

		val requesterState = placeRequester(requesterPos, Direction.NORTH)
		requesterState.target(ItemResource.of(ItemStack(Items.DIAMOND)), 6)

		// A provider on the requester's own tile, facing the chest, is what gives RequestFulfillment
		// something to pull from - a plain chest isn't a source until something opts it in.
		val requesterTile = getBlockEntity(requesterPos) as MultipartBlockEntity
		val providerState = requesterTile.hooks.getOrPut(Direction.EAST.name) { ProviderHookType.createState() } as ProviderHookState
		providerState.routing = RoutingModule(mode = FilterMode.BLACKLIST)
		placeCreativePressureSource(requesterPos.above())

		runAfterDelay(160) {
			val held = amountHeld(interfaceState, ItemResource.of(ItemStack(Items.DIAMOND)))
			assertTrue(held == 0L) {
				"Expected nothing parked on the seam when there is nothing behind it to stock, got $held"
			}
			val source = (getBlockEntity(sourcePos) as ChestBlockEntity).getItem(0)
			assertTrue(source.count == 32) {
				"Expected the requester's own source to be untouched, got $source"
			}
			succeed()
		}
	}

	/**
	 * A requester facing an interface it can already **reach through pipe** does nothing.
	 *
	 * The point of pointing a requester at an interface is to supply the subnet on the *far* side of
	 * a boundary. A pipe run looping around that boundary rejoins the two sides into one subnet, and
	 * then there is no far side: every request would pull out of this network and deliver straight
	 * back into it, churning items to no effect. The hooks facing each other is not enough to
	 * establish a crossing - a boundary severs the flood fill locally, but a loop reconnects it.
	 *
	 * The interface must also keep stocking *itself* here. Standing its own down for a partner that
	 * turns out to be inert would leave it idle for no reason, which is the same bug seen from the
	 * other side.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testARequesterDoesNothingForAnInterfaceOnItsOwnNetwork() {
		val interfacePos = BlockPos(0, 2, 0)
		val requesterPos = BlockPos(0, 2, 1)

		// The loop around the seam: both segments also meet via (1,2,0)-(1,2,1), so the boundary edge
		// between them severs nothing and the two are one subnet.
		setBlock(BlockPos(1, 2, 0), BlockRegistry.Pipe.defaultBlockState())
		setBlock(BlockPos(1, 2, 1), BlockRegistry.Multipart.defaultBlockState())
		(getBlockEntity(BlockPos(1, 2, 1)) as MultipartBlockEntity).pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)

		setBlock(interfacePos, BlockRegistry.Multipart.defaultBlockState())
		val interfaceTile = getBlockEntity(interfacePos) as MultipartBlockEntity
		interfaceTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.SOUTH.name) { InterfaceHookType.createState() } as InterfaceHookState

		val requesterState = placeRequester(requesterPos, Direction.NORTH)
		requesterState.target(ItemResource.of(ItemStack(Items.DIAMOND)), 6)
		placeCreativePressureSource(requesterPos.above())

		// A real source on the requester's own network, so "nothing moved" below is a decision rather
		// than an accident - without the same-subnet guard these diamonds would be shuttled into the
		// interface, which is exactly the churn being prevented.
		val sourcePos = BlockPos(1, 2, 2)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 32))
		val loopTile = getBlockEntity(BlockPos(1, 2, 1)) as MultipartBlockEntity
		val provider = loopTile.hooks.getOrPut(Direction.SOUTH.name) { ProviderHookType.createState() } as ProviderHookState
		provider.routing = RoutingModule(mode = FilterMode.BLACKLIST)

		// Every segment in the loop, once all of them exist - a connection bit is only right after
		// both sides of it are placed.
		for (pos in listOf(interfacePos, requesterPos, BlockPos(1, 2, 0), BlockPos(1, 2, 1))) refreshConnections(pos)

		runAfterDelay(60) {
			val serverLevel = level as ServerLevel
			assertTrue(RequestFulfillment.sharesSubnet(serverLevel, absolutePos(requesterPos), absolutePos(interfacePos))) {
				"This test is only meaningful while the loop actually joins the two sides into one subnet"
			}

			val status = requesterStatus(serverLevel, absolutePos(requesterPos), Direction.NORTH, requesterState)
			assertTrue(status.detail.contains("same network")) {
				"Expected the requester to report that there is nothing to carry across, got $status"
			}
			assertTrue(amountHeld(interfaceState, ItemResource.of(ItemStack(Items.DIAMOND))) == 0L) {
				"Expected nothing to have been shuttled into an interface on the requester's own network"
			}
			succeed()
		}
	}
}
