package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.crafting.PatternItemData
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** GameTest coverage for [net.kernelpanicsoft.tubularstorage.crafting.PatternItem]'s stack-backed data - see `docs/design/m4-crafting-automation.md`. */
@Suppress("unused")
class PatternItemGameTest {
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testFreshPatternStackIsBlank() {
		val stack = ItemStack(ItemRegistry.Pattern)
		assertTrue(PatternItemData(stack).pattern == Pattern.EMPTY) { "Expected a fresh pattern item to have no encoded pattern" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testEncodedPatternSurvivesACopy() {
		val stack = ItemStack(ItemRegistry.Pattern)
		val pattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_LOG))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)),
			kind = PatternKind.PROCESSING,
		)
		PatternItemData(stack).pattern = pattern

		val copy = stack.copy()
		assertTrue(PatternItemData(copy).pattern == pattern) { "Expected a copied stack to carry the same encoded pattern, got ${PatternItemData(copy).pattern}" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testIdenticallyEncodedStacksStackTogether() {
		val pattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.IRON_INGOT))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.IRON_NUGGET)), 9)),
			kind = PatternKind.PROCESSING,
		)
		val a = ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern }
		val b = ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern }
		assertTrue(ItemStack.isSameItemSameComponents(a, b)) { "Expected two stacks encoded with an equal pattern to be stackable, got $a / $b" }

		val differentPattern = pattern.copy(kind = PatternKind.CRAFTING)
		val c = ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = differentPattern }
		assertTrue(!ItemStack.isSameItemSameComponents(a, c)) { "Expected differently-encoded pattern stacks to NOT be stackable, got $a / $c" }
		succeed()
	}
}
