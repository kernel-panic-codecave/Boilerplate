package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.crafting.PatternItemData
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * GameTest coverage for [PatternProviderHookType]'s own reactive tick behavior - see
 * `docs/design/m4-crafting-automation.md`.
 */
@Suppress("unused")
class PatternProviderHookGameTest {
	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testTriggersAssemblyTableOnceIngredientsArePresent() {
		val tablePos = BlockPos(0, 2, 0)
		val hookPos = BlockPos(0, 2, 1)
		setBlock(tablePos, BlockRegistry.AssemblyTable.defaultBlockState())
		setBlock(hookPos, BlockRegistry.Hook.defaultBlockState())

		val hook = getBlockEntity(hookPos) as HookBlockEntity
		hook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val hookState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState

		val pattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_LOG))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)),
			kind = PatternKind.PROCESSING,
		)
		hookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })

		val table = getBlockEntity(tablePos) as AssemblyTableBlockEntity
		table.grid.get(0).set(ItemStack(Items.OAK_LOG))

		succeedWhen {
			assertTrue(table.activePattern == pattern) { "Expected the hook to have kicked off beginProcessing once the grid held the pattern's ingredients, got activePattern=${table.activePattern}" }
			assertTrue(hookState.activeSlot == 0) { "Expected the hook's own activeSlot to track slot 0 while the table processes it, got ${hookState.activeSlot}" }
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testDoesNothingWithoutMatchingIngredients() {
		val tablePos = BlockPos(0, 2, 0)
		val hookPos = BlockPos(0, 2, 1)
		setBlock(tablePos, BlockRegistry.AssemblyTable.defaultBlockState())
		setBlock(hookPos, BlockRegistry.Hook.defaultBlockState())

		val hook = getBlockEntity(hookPos) as HookBlockEntity
		hook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val hookState = hook.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState

		val pattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_LOG))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)),
			kind = PatternKind.PROCESSING,
		)
		hookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })

		val table = getBlockEntity(tablePos) as AssemblyTableBlockEntity
		// Grid stays empty - nothing for the hook to match against.

		runAfterDelay(30) {
			assertTrue(table.activePattern == null && hookState.activeSlot == null) {
				"Expected an empty grid to never trigger processing, got activePattern=${table.activePattern} activeSlot=${hookState.activeSlot}"
			}
			succeed()
		}
	}
}
