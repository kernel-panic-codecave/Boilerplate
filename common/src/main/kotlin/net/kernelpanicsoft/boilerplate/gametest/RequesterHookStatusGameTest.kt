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
		return tile.hooks.getOrPut(face.name) { RequesterHookType.createState() } as RequesterHookState
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
	 * The behaviour behind that readout: a requester keeps the interface it faces stocked with **its
	 * own** order, and the interface does not undo the work.
	 *
	 * The second half is the part that needs pinning. An interface ordinarily drains anything its own
	 * ghost row does not name straight back out ([InterfaceHookType]'s `drainExcess`), which for a
	 * requester-supplied resource means everything that arrives - the two would trade the same stack
	 * back and forth forever. While a requester is driving it, the interface stands its own stocking
	 * down and honours that order instead.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testARequesterKeepsTheInterfaceStockedWithItsOwnOrder() {
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

		succeedWhen {
			val held = amountHeld(interfaceState, ItemResource.of(ItemStack(Items.DIAMOND)))
			assertTrue(held == 6L) {
				"Expected the interface to settle holding exactly the requester's order of 6 diamonds - no fewer (not supplied) and no more (drained back), got $held"
			}
		}
	}
}
