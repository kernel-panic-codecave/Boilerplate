package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/**
 * GameTest coverage that a [SortingHookState.filter] write actually *persists*, guarding a real,
 * previously-reported bug in the ghost-grid version this slot replaced: a filter write only ever
 * mutated the nested hook state in memory, never marking the owning [MultipartBlockEntity] dirty
 * the way [net.kernelpanicsoft.boilerplate.network.UpdateSortingRoutingPacket] already did for the
 * sibling [SortingHookState.routing] field - so a placed filter looked correct right up until the
 * block entity was reconstructed, then came back missing.
 *
 * The filter is a real single vanilla-slot [net.kernelpanicsoft.archie.transfer.ArchieItemStorage]
 * now rather than a ghost grid, so persistence rides on a different mechanism entirely
 * ([MultipartBlockEntity.tick]'s own `hooks.touch()` re-serializing every nested hook state, the
 * same one that already carries [net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState.output]).
 * The property worth pinning is unchanged though, and is exactly what regressed before: a card put
 * in the slot has to survive, and has to make the chunk save-worthy. Asserted via
 * [net.minecraft.world.level.chunk.ChunkAccess.isUnsaved] flipping to `true`, since a same-session
 * in-memory re-read alone would pass regardless (the underlying compound mutates unconditionally;
 * only the *save-worthiness* flag ever went missing).
 */
@Suppress("unused")
class HookFilterPersistenceGameTest {
	@GameTest(template = SMALL, timeoutTicks = 60)
	fun GameTestHelper.testFilterCardInFilterSlotPersists() {
		val hookPos = BlockPos(0, 2, 0)
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())
		val hook = getBlockEntity(hookPos) as MultipartBlockEntity
		hook.hooks.getOrPut(Direction.NORTH.name) { FilterHookType.createState() }

		val level = level as ServerLevel
		val absoluteHookPos = absolutePos(hookPos)
		val card = ItemResource.of(ItemStack(ItemRegistry.ItemFilterCard))
		hook.filterFor(Direction.NORTH).insert(card, 1, false)
		level.getChunkAt(absoluteHookPos).isUnsaved = false

		succeedWhen {
			assertTrue(hook.filterFor(Direction.NORTH).get(0).resource == card) {
				"Expected the filter card to still be in the hook's own filter slot, got ${hook.filterFor(Direction.NORTH).get(0).resource}"
			}
			assertTrue(level.getChunkAt(absoluteHookPos).isUnsaved) {
				"Expected the hook's own tick to have marked the chunk dirty for saving - otherwise a placed filter card never actually persists, it only looks right until the block entity is reconstructed"
			}
		}
	}

	/** A non-[net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem] is refused outright - the slot only ever holds filter cards, which is what lets [SortingHookState.accepts] treat its contents as one unconditionally. */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testFilterSlotRejectsANonFilterCard() {
		val hookPos = BlockPos(0, 2, 0)
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())
		val hook = getBlockEntity(hookPos) as MultipartBlockEntity
		hook.hooks.getOrPut(Direction.NORTH.name) { FilterHookType.createState() }

		val diamond = ItemResource.of(ItemStack(net.minecraft.world.item.Items.DIAMOND))
		val inserted = hook.filterFor(Direction.NORTH).insert(diamond, 1, false)

		assertTrue(inserted == 0L) { "Expected a plain diamond to be refused by the filter-card-only slot, but $inserted went in" }
		assertTrue(hook.filterFor(Direction.NORTH).get(0).resource.isBlank) {
			"Expected the filter slot to still be empty after refusing a non-card, got ${hook.filterFor(Direction.NORTH).get(0).resource}"
		}
		succeed()
	}
}
