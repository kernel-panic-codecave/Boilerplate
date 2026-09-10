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
		val card = ItemResource.of(ItemStack(ItemRegistry.ResourceFilterCard))
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

	/**
	 * A multipart's whole persisted state survives a real NBT round trip: its hooks (with each
	 * one's own nested contents), its encasement, and its pipe type.
	 *
	 * Where [testFilterCardInFilterSlotPersists] pins that a write makes the chunk *save-worthy*,
	 * this pins what the save itself is worth - it writes the block entity out and reads it back,
	 * which is the step nothing else here covered. Worth having on its own terms, and specifically
	 * because the failure it would catch is indistinguishable from the outside from a rebuild
	 * landing under a live game: every field back at its declared default, the block entity still
	 * there and empty. That has happened, and being able to tell the two apart afterwards is the
	 * whole point of pinning it.
	 */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testMultipartStateSurvivesAnNbtRoundTrip() {
		val hookPos = BlockPos(0, 2, 0)
		val tile = placeCraftingBuffer(hookPos)
		val hook = tile.hooks.getOrPut(Direction.NORTH.name) { FilterHookType.createState() } as SortingHookState
		val card = ItemResource.of(ItemStack(ItemRegistry.ResourceFilterCard))
		tile.filterFor(Direction.NORTH).insert(card, 1, false)
		hook.routing = hook.routing.copy(priority = 7)
		tile.hooks.touch()
		val pipeId = tile.pipeBlockId

		val saved = tile.saveWithoutMetadata(level.registryAccess())
		tile.loadWithComponents(saved, level.registryAccess())

		assertTrue(tile.hooks.size == 1) { "Expected the one hook back after a round trip, got ${tile.hooks.size}" }
		assertTrue(tile.filterFor(Direction.NORTH).get(0).resource == card) {
			"Expected the hook's own filter card back, got ${tile.filterFor(Direction.NORTH).get(0).resource}"
		}
		assertTrue((tile.hooks[Direction.NORTH.name] as SortingHookState).routing.priority == 7) {
			"Expected the hook's own nested routing back, got ${(tile.hooks[Direction.NORTH.name] as SortingHookState).routing.priority}"
		}
		assertTrue(tile.encasement.value != null) { "Expected the encasement back after a round trip, got nothing" }
		assertTrue(tile.pipeBlockId == pipeId) { "Expected the pipe type back after a round trip, got ${tile.pipeBlockId}" }
		succeed()
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
