package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternItemData
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.pipe.attachment.exposedStorageFor
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.util.resourceCell
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * **Every** pipe face accepts a push and routes it, with no hook of any kind involved.
 *
 * This is what makes a machine with its own auto-output automatable by a Pattern Provider alone:
 * the provider pushes ingredients in, the machine pushes its result back into the same pipe, and
 * the network carries it away. Before this, only an interface hook had a pass-through, so a machine
 * that pushed rather than waiting to be pulled from had nowhere to push.
 */
@Suppress("unused")
class PipePassThroughGameTest {

	/**
	 * The kind-generic face - the one a *loader-registered* kind is reached through - has the same
	 * pass-through every face does.
	 *
	 * [exposedStorageFor] is what every kind's face resolves through - each of them registered in one
	 * go by [net.kernelpanicsoft.boilerplate.network.exposeResourceStorage] - and it had no
	 * pass-through fallback at all: a face with no hook that knew about that kind answered `null`,
	 * so a Mekanism machine pointed at a bare pipe found no capability to push chemicals into.
	 * Exercised here with kinds this module can name, since it is the *path* that was missing, not
	 * anything per-kind.
	 */
	@GameTest(template = SMALL, timeoutTicks = 60)
	fun GameTestHelper.testTheKindGenericFaceAlsoPassesThrough() {
		val pipePos = BlockPos(1, 2, 0)
		setBlock(pipePos, BlockRegistry.Multipart.defaultBlockState())
		val tile = getBlockEntity(pipePos) as MultipartBlockEntity
		tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)

		runAfterDelay(5) {
			for (kind in listOf(ResourceKindRegistry.Item, ResourceKindRegistry.Fluid)) {
				val face = tile.exposedStorageFor<ResourceComponent>(kind, Direction.WEST)
				assertTrue(face != null) {
					"Expected a hookless face to expose a ${kind.kindTag} storage through the kind-generic path - " +
						"a loader-registered kind has no other way in"
				}
				assertTrue(face!!.size() > 0) {
					"Expected that ${kind.kindTag} face to offer a slot - a machine inserting per-slot ignores a storage with none"
				}
			}
			succeed()
		}
	}

	/**
	 * A machine pushing its *result* into the very face a Pattern Provider sits on gets it routed
	 * away, not swallowed.
	 *
	 * This is the arrangement [PipePassThroughGameTest]'s own KDoc describes - "the provider pushes
	 * ingredients in, the machine pushes its result back into the same pipe" - and the one that
	 * makes a Pattern Provider the *only* hook a machine needs. It did not work: a hook exposing an
	 * item storage replaced the face's pass-through outright rather than backing it, so the machine
	 * met the provider's own ingredient staging buffer, which refuses anything that is not an input
	 * of a held pattern. The result had nowhere to go and stayed in the machine, and the job that
	 * was waiting on it never finished.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAMachinePushingItsOutputIntoAPatternProviderFaceIsRouted() {
		val hookPos = BlockPos(1, 2, 0)
		val middlePos = BlockPos(1, 2, 1)
		val destPos = BlockPos(1, 2, 2)
		val serverLevel = level as ServerLevel

		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())
		val hookTile = getBlockEntity(hookPos) as MultipartBlockEntity
		hookTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		// WEST is where the machine lives, so it is the face the machine's auto-output pushes into
		// and the face the provider occupies - the whole point of the arrangement.
		val hookState = hookTile.hooks.getOrPut(Direction.WEST.name) { PatternProviderHookType.createState() }
		hookState.patterns[0].set(
			ItemStack(ItemRegistry.Pattern).also {
				PatternItemData(it).pattern = Pattern(
					inputs = listOf(ItemStack(Items.IRON_INGOT).resourceCell),
					outputs = listOf(ItemStack(Items.IRON_BLOCK).resourceCell),
					kind = PatternKind.PROCESSING,
				)
			}
		)
		setBlock(middlePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())
		level.setBlock(absolutePos(hookPos), Block.updateFromNeighbourShapes(serverLevel.getBlockState(absolutePos(hookPos)), serverLevel, absolutePos(hookPos)), Block.UPDATE_ALL)

		val ironBlock = ItemResource.of(ItemStack(Items.IRON_BLOCK))

		runAfterDelay(5) {
			val face = ItemApi.BLOCK.find(serverLevel, absolutePos(hookPos), Direction.WEST)
			assertTrue(face != null) { "Expected the provider's own face to expose a storage for the machine to push into" }

			val accepted = face!!.insert(ironBlock, 1, false)
			assertTrue(accepted == 1L) {
				"Expected the provider's face to take the machine's result for routing, got $accepted - " +
					"the pattern's ingredient buffer refuses anything that is not one of its inputs, so the result has nowhere to go"
			}

			runAfterDelay(120) {
				val dest = getBlockEntity(destPos) as ChestBlockEntity
				assertTrue(dest.getItem(0).`is`(Items.IRON_BLOCK)) {
					"Expected the machine's result to have travelled past the provider to the chest, got ${dest.getItem(0)}"
				}
				succeed()
			}
		}
	}

	/**
	 * The other half of the same face: what a Pattern Provider staged is still *readable* through it.
	 *
	 * Guards the fix for [testAMachinePushingItsOutputIntoAPatternProviderFaceIsRouted] from being
	 * "replace the hook's surface with a bare pass-through", which would hide the pattern's own
	 * buffer from anything looking at that face. (Extraction is not checked: [PatternBufferIO]
	 * refuses it by design - staged ingredients belong to the run that staged them.)
	 */
	@GameTest(template = SMALL, timeoutTicks = 60)
	fun GameTestHelper.testAPatternProvidersBufferIsStillReadableThroughItsFace() {
		val hookPos = BlockPos(1, 2, 0)
		val serverLevel = level as ServerLevel

		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())
		val hookTile = getBlockEntity(hookPos) as MultipartBlockEntity
		hookTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val hookState = hookTile.hooks.getOrPut(Direction.WEST.name) { PatternProviderHookType.createState() }
		hookState.patterns[0].set(
			ItemStack(ItemRegistry.Pattern).also {
				PatternItemData(it).pattern = Pattern(
					inputs = listOf(ItemStack(Items.IRON_INGOT).resourceCell),
					outputs = listOf(ItemStack(Items.IRON_BLOCK).resourceCell),
					kind = PatternKind.PROCESSING,
				)
			}
		)

		val ingot = ItemResource.of(ItemStack(Items.IRON_INGOT))
		// Staged the way a Crafting CPU stages it - straight into the buffer, never through the face.
		hookState.bufferFor(0).insert(ingot, 3, false)

		runAfterDelay(5) {
			val face = ItemApi.BLOCK.find(serverLevel, absolutePos(hookPos), Direction.WEST)
			assertTrue(face != null) { "Expected the provider's face to still expose a storage" }
			val visible = (0 until face!!.size()).sumOf { if (face.getResource(it) == ingot) face.getAmount(it) else 0L }
			assertTrue(visible == 3L) { "Expected the staged ingredients to be visible through the face, saw $visible" }
			succeed()
		}
	}

	/**
	 * A push into a bare pipe reaches a chest further along the run - accepted at the face, and
	 * actually delivered rather than merely promised.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAPushIntoAHooklessPipeIsRoutedToADestination() {
		val pushedIntoPos = BlockPos(1, 2, 0)
		val middlePos = BlockPos(1, 2, 1)
		val destPos = BlockPos(1, 2, 2)
		val serverLevel = level as ServerLevel

		setBlock(pushedIntoPos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(middlePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))

		// A route search only sees segments the network manager has registered, which happens on
		// tick - pushing before the first one finds nothing at all, whatever the face says.
		runAfterDelay(5) {
			// Exactly what a machine's own auto-output does: find the storage on the face pointing
			// at it and insert.
			val face = ItemApi.BLOCK.find(serverLevel, absolutePos(pushedIntoPos), Direction.WEST)
			assertTrue(face != null) { "Expected a hookless pipe face to expose a storage for a machine to push into" }
			assertTrue(face!!.size() > 0) {
				"Expected that face to offer at least one slot - a machine that inserts per-slot ignores a storage with none"
			}

			val accepted = face.insert(diamond, 4, false)
			assertTrue(accepted == 4L) { "Expected the pipe to accept the whole push for routing, got $accepted" }

			runAfterDelay(120) {
				val dest = getBlockEntity(destPos) as ChestBlockEntity
				assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 4) {
					"Expected the pushed diamonds to have travelled to the chest, got ${dest.getItem(0)}"
				}
				succeed()
			}
		}
	}

	/**
	 * A pipe with nowhere to send something refuses the push outright rather than swallowing it.
	 *
	 * The half that makes a pass-through safe: a machine's own auto-output backs up and holds its
	 * result, exactly as it would against a full chest, instead of the pipe accepting an item it
	 * cannot deliver and stranding it.
	 */
	@GameTest(template = SMALL, timeoutTicks = 100)
	fun GameTestHelper.testAPushWithNowhereToGoIsRefused() {
		val pushedIntoPos = BlockPos(1, 2, 0)
		val serverLevel = level as ServerLevel

		setBlock(pushedIntoPos, BlockRegistry.Pipe.defaultBlockState())

		runAfterDelay(5) {
			val face = ItemApi.BLOCK.find(serverLevel, absolutePos(pushedIntoPos), Direction.WEST)
			assertTrue(face != null) { "Expected a hookless pipe face to expose a storage even with nothing to route to" }

			val accepted = face!!.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 4, false)
			assertTrue(accepted == 0L) { "Expected a pipe with no reachable destination to refuse the push, got $accepted" }
			succeed()
		}
	}
}
