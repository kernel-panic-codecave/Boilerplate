package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternItemData
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.resource.resourceCell
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** GameTest coverage for [net.kernelpanicsoft.boilerplate.crafting.PatternItem]'s stack-backed data - see `docs/design/m4-crafting-automation.md`. */
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
			inputs = listOf(ItemStack(Items.OAK_LOG).resourceCell),
			outputs = listOf(ItemStack(Items.OAK_PLANKS, 4).resourceCell),
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
			inputs = listOf(ItemStack(Items.IRON_INGOT).resourceCell),
			outputs = listOf(ItemStack(Items.IRON_NUGGET, 9).resourceCell),
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
