package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.FilterHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardTarget
import net.kernelpanicsoft.tubularstorage.pipe.hook.filter.HookGhostSlotItemAccess
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType

/**
 * GameTest coverage for a real, previously-reported bug: a [SortingHookState.filter] ghost-slot
 * write only ever mutated the nested hook state in memory - never marking the owning
 * [MultipartBlockEntity] dirty the way [net.kernelpanicsoft.tubularstorage.network.UpdateSortingRoutingPacket]
 * already does for the sibling [SortingHookState.routing] field - so a placed filter never actually
 * persisted (a chunk save/reload, or simply the block entity being reconstructed, showed it
 * missing again, even though the very same session's own in-memory copy looked correct right up
 * until then). Both real write paths ([FilterCardTarget.HookFilterSlot.write], the direct
 * drag-a-card-into-the-grid case, and [HookGhostSlotItemAccess.commit], the close-a-`FilterCardMenu`
 * case) are covered. Asserted via [net.minecraft.world.level.chunk.ChunkAccess.isUnsaved] flipping
 * to `true` - the concrete, testable effect of the missing dirty-marking, since a same-session
 * in-memory re-read alone would pass regardless of the bug (the underlying NBT compound mutates
 * unconditionally; only the *save-worthiness* flag was ever missing).
 */
@Suppress("unused")
class FilterGhostSlotPersistenceGameTest {
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testDirectGhostSlotWriteMarksTheChunkDirty() {
		val hookPos = BlockPos(0, 2, 0)
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())
		val hook = getBlockEntity(hookPos) as MultipartBlockEntity
		val hookState = hook.hooks.getOrPut(Direction.NORTH.name) { FilterHookType.createState() } as SortingHookState

		val level = level as ServerLevel
		val absoluteHookPos = absolutePos(hookPos)
		level.getChunkAt(absoluteHookPos).isUnsaved = false

		val player = makeMockPlayer(GameType.CREATIVE)
		val resource = ItemResource.of(ItemStack(Items.DIAMOND))
		FilterCardTarget.HookFilterSlot(absoluteHookPos, Direction.NORTH, 0).write(level, player, resource)

		assertTrue(hookState.filter[0] == resource) { "Expected the write to still update the in-memory filter entry itself, got ${hookState.filter[0]}" }
		assertTrue(level.getChunkAt(absoluteHookPos).isUnsaved) {
			"Expected the ghost-slot write to have marked the chunk dirty for saving - otherwise the placed filter never actually persists, it only looks right until the block entity is reconstructed"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testFilterCardMenuCommitMarksTheChunkDirty() {
		val hookPos = BlockPos(0, 2, 0)
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())
		val hook = getBlockEntity(hookPos) as MultipartBlockEntity
		hook.hooks.getOrPut(Direction.NORTH.name) { FilterHookType.createState() }

		val level = level as ServerLevel
		val absoluteHookPos = absolutePos(hookPos)
		val access = HookGhostSlotItemAccess(level, absoluteHookPos, Direction.NORTH, 0)
		access.getStack() // materializes `cached` before the dirty flag gets cleared below

		level.getChunkAt(absoluteHookPos).isUnsaved = false
		access.commit()

		assertTrue(level.getChunkAt(absoluteHookPos).isUnsaved) {
			"Expected closing a FilterCardMenu (its own commit-on-close) to have marked the chunk dirty for saving, same as a direct ghost-slot write"
		}
		succeed()
	}
}
