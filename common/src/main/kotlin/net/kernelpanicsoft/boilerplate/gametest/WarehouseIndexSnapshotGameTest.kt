package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.warehouse.Bounds
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseIndex
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * A warehouse's persisted index survives a reload **including its empty racks**.
 *
 * The snapshot is a cache of a scan, and the expensive half of that scan is discovering *where the
 * containers are* - not what is in them. An empty rack is still a rack: it is where put-away wants
 * to send things ([WarehouseIndex.availableSlots] is exactly the "somewhere with room" list). Losing
 * it on load meant a warehouse came back up unable to stow anything into any rack that happened to
 * be empty at save time, until the low-frequency background audit eventually rediscovered it.
 */
@Suppress("unused")
class WarehouseIndexSnapshotGameTest {

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testEmptyRacksSurviveAnIndexSnapshotRoundTrip() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val stockedRackPos = BlockPos(3, 2, 4)
		val emptyRackPos = BlockPos(4, 2, 4)

		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(stockedRackPos, Blocks.CHEST.defaultBlockState())
		setBlock(emptyRackPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(stockedRackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 5))

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))

		succeedWhen {
			// Only meaningful once the live scan has actually found both chests.
			assertTrue(absolutePos(emptyRackPos) in controller.index.knownContainers) {
				"Expected the live scan to know about the empty rack before the round trip is tested"
			}

			// Exactly what a save/load does: flatten to NBT-backed form, then rebuild a fresh index
			// from it, the way WarehouseControllerBlockEntity does on load.
			val restored = WarehouseIndex()
			restored.restoreFrom(controller.index.toSnapshot(), absolutePos(controllerPos))
			restored.updateIndex(level as ServerLevel, absolutePos(controllerPos))

			assertTrue(absolutePos(stockedRackPos) in restored.knownContainers) {
				"Expected the stocked rack to survive the snapshot, got ${restored.knownContainers}"
			}
			assertTrue(absolutePos(emptyRackPos) in restored.knownContainers) {
				"Expected the EMPTY rack to survive the snapshot too, got ${restored.knownContainers}"
			}
			assertTrue(restored.availableSlots.any { it.first == absolutePos(emptyRackPos) }) {
				"Expected the empty rack to be a put-away destination after the round trip, got ${restored.availableSlots}"
			}
		}
	}

	/**
	 * A warehouse whose racks are **all** empty still has a snapshot worth restoring.
	 *
	 * The controller decides whether to restore at all before it looks at anything else, and that
	 * gate used to read `entries.isNotEmpty()`. An all-empty warehouse has no entries but plenty of
	 * containers, so its whole scan was written to NBT and then thrown away unread on every single
	 * load - the racks were persisted and the restore simply declined to use them. This pins the
	 * gate's own input rather than the load path, which a gametest cannot reload.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testAnAllEmptyWarehouseStillHasASnapshotWorthRestoring() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val emptyRackPos = BlockPos(4, 2, 4)

		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(emptyRackPos, Blocks.CHEST.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))

		succeedWhen {
			assertTrue(absolutePos(emptyRackPos) in controller.index.knownContainers) {
				"Expected the live scan to find the empty rack before the snapshot is checked"
			}
			val snapshot = controller.index.toSnapshot()
			assertTrue(snapshot.entries.isEmpty()) {
				"This test is only meaningful while nothing is stored - it has stock, so it no longer covers the gate"
			}
			assertTrue(snapshot.hasData) {
				"Expected an all-empty warehouse's snapshot to still be worth restoring, got $snapshot"
			}
			assertTrue(absolutePos(emptyRackPos) in snapshot.containers) {
				"Expected the empty rack's position to be in the snapshot, got ${snapshot.containers}"
			}
		}
	}
}
