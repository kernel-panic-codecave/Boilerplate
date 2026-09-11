package net.kernelpanicsoft.boilerplate.compat

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternTerminalHookState
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.ShapedRecipe

/**
 * A pattern terminal's ghost grid as a recipe viewer wants to fill it - the kind the terminal has to
 * be in, and [PatternTerminalHookState.GRID_SIZE] cells a side.
 *
 * Viewer-neutral on purpose. Each of JEI, REI and EMI has its own way of naming a recipe's
 * ingredients and its own way of saying whether what it is showing is a vanilla crafting recipe;
 * none of them has any business deciding what a pattern looks like. So each plugin does only the
 * part nothing else can do - turning its own ingredients into resources - and hands the result to
 * [patternFillOf] for everything after that.
 *
 * @property kind what the terminal must be switched to before these cells mean anything.
 * @property inputs exactly [PatternTerminalHookState.GRID_SIZE] cells, blank where empty.
 * @property outputs the same, and left entirely blank for [PatternKind.CRAFTING] - that kind
 *   derives its output by matching the grid against a real recipe rather than storing one.
 */
data class PatternFill(
	val kind: PatternKind,
	val inputs: List<ResourceStack<ResourceComponent>>,
	val outputs: List<ResourceStack<ResourceComponent>>,
)

/**
 * The pattern a shown recipe should author.
 *
 * @param craftingGrid the recipe's ingredients keyed by 3x3 grid index, when the viewer is showing a
 *   genuine vanilla crafting recipe and can say where each one sits. `null` for every other recipe
 *   category, which is most of them.
 * @param inputs every ingredient the recipe consumes, in the viewer's own display order - what a
 *   [PatternKind.PROCESSING] pattern's unordered bag is filled from, and the fallback when
 *   [craftingGrid] cannot be used.
 * @param outputs everything the recipe produces, in display order.
 * @return the cells to write and the kind to write them in.
 *
 * [PatternKind.CRAFTING] is chosen only when the recipe really is one: a positional grid of items
 * that fits in 3x3 and produces a single output. A crafting pattern is re-matched against a live
 * vanilla recipe when it is used, so filling one from anything else authors a pattern that can
 * never resolve - better to author a processing pattern, which stores exactly what it was told and
 * needs no recipe to exist at all.
 *
 * Anchored at the grid's own top-left, like every other transfer this mod does: vanilla's shaped
 * matching tries each valid offset itself, so a 2x2 recipe placed in the corner still resolves.
 */
fun patternFillOf(
	craftingGrid: Map<Int, ResourceStack<ResourceComponent>>?,
	inputs: List<ResourceStack<ResourceComponent>>,
	outputs: List<ResourceStack<ResourceComponent>>,
): PatternFill {
	val size = PatternTerminalHookState.GRID_SIZE
	val asCrafting = craftingGrid
		?.takeIf { grid -> grid.isNotEmpty() && grid.keys.all { it in 0 until size } }
		?.takeIf { grid -> grid.values.all { it.resource is ItemResource } }
		?.takeIf { outputs.size <= 1 }

	if (asCrafting != null) {
		// One each: a crafting recipe consumes a single item per cell whatever the viewer reports,
		// and a cell asking for more would leave the encoded pattern demanding a multiple the
		// recipe never uses - see PatternTerminalHookState.ghostInputAmounts.
		val cells = List(size) { index -> asCrafting[index]?.let { ResourceStack(it.resource, 1L) } ?: BLANK }
		return PatternFill(PatternKind.CRAFTING, cells, List(size) { BLANK })
	}

	return PatternFill(PatternKind.PROCESSING, packed(inputs, size), packed(outputs, size))
}

/** [stacks] laid into [size] cells in order, ignoring anything past the end and padding the rest blank. */
private fun packed(stacks: List<ResourceStack<ResourceComponent>>, size: Int): List<ResourceStack<ResourceComponent>> =
	List(size) { index -> stacks.getOrNull(index)?.takeIf { it.amount > 0 } ?: BLANK }

/** An empty cell - the same blank every ghost grid starts out full of. */
private val BLANK: ResourceStack<ResourceComponent> get() = ResourceStack(ItemResource.BLANK, 0L)

/**
 * [recipe]'s ingredients keyed by 3x3 grid index, or `null` when it is not a vanilla crafting recipe
 * at all - which is the answer [patternFillOf] needs to send a pattern to [PatternKind.PROCESSING].
 *
 * Read off the recipe itself rather than off whatever the viewer drew, and shared by all three
 * plugins for that reason: each viewer lays a crafting recipe out to suit its own panel, and a cell
 * index inferred from that layout is a guess about the viewer, where [ShapedRecipe]'s own
 * width/height is the recipe's actual shape. A shapeless recipe has no shape to preserve, so its
 * ingredients simply fill from the first cell.
 *
 * Each cell takes the first item its [net.minecraft.world.item.crafting.Ingredient] accepts. A
 * tag-backed ingredient has several and a pattern can only name one; the first is what every other
 * transfer in this mod already picks, so a pattern authored here matches what the crafting terminal
 * would have filled.
 *
 * @return the grid, or `null` for a non-crafting recipe, a recipe wider or taller than three, or one
 *   whose ingredients are all empty.
 */
fun craftingGridOf(recipe: RecipeHolder<*>?): Map<Int, ResourceStack<ResourceComponent>>? {
	val crafting = recipe?.value as? CraftingRecipe ?: return null
	val width = (crafting as? ShapedRecipe)?.width ?: PATTERN_GRID_WIDTH
	val height = (crafting as? ShapedRecipe)?.height ?: PATTERN_GRID_WIDTH
	if (width > PATTERN_GRID_WIDTH || height > PATTERN_GRID_WIDTH) return null

	val grid = HashMap<Int, ResourceStack<ResourceComponent>>()
	crafting.ingredients.forEachIndexed { index, ingredient ->
		val stack = ingredient.items.firstOrNull()?.takeUnless { it.isEmpty } ?: return@forEachIndexed
		val cell = index / width * PATTERN_GRID_WIDTH + index % width
		if (cell < PatternTerminalHookState.GRID_SIZE) grid[cell] = ResourceStack(ItemResource.of(stack), 1L)
	}
	return grid.takeIf { it.isNotEmpty() }
}

/** The pattern grid's own width - three, the width a vanilla crafting recipe is matched at. */
const val PATTERN_GRID_WIDTH = 3
