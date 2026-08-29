package net.kernelpanicsoft.boilerplate.compat.emi

import dev.emi.emi.api.EmiPlugin
import dev.emi.emi.api.EmiRegistry
import dev.emi.emi.api.EmiStackProvider
import dev.emi.emi.api.recipe.EmiRecipe
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories
import dev.emi.emi.api.recipe.handler.EmiCraftContext
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler
import dev.emi.emi.api.stack.EmiStack
import dev.emi.emi.api.stack.EmiStackInteraction
import dev.emi.emi.api.widget.Bounds
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookScreen
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTerminalHookMenu
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.kernelpanicsoft.boilerplate.util.itemStack
import net.minecraft.world.inventory.Slot

/**
 * Unlike REI/JEI, EMI ships no *published* loader-agnostic api artifact under its own main
 * coordinates (`emi-fabric`/`emi-neoforge` each bundle their own copy of the same shared source,
 * confirmed by diffing their class lists - only two loader-specific ingredient-wrapper classes
 * differ) - but it does separately publish that shared source as its own intermediary-mapped
 * artifact, `dev.emi:emi-xplat-intermediary` (see `docs/design/m6-polish-parity.md`), which
 * Architectury Loom remaps per real platform exactly like Archie's own published modules. So this
 * plugin lives here in `common` too, same as [net.kernelpanicsoft.boilerplate.compat.rei.BoilerplateREIPlugin]/
 * [net.kernelpanicsoft.boilerplate.compat.jei.BoilerplateJEIPlugin] - no per-loader duplication
 * needed after all. Discovery still differs per loader, though: Fabric needs the `"emi"` entrypoint
 * declared in `fabric.mod.json` pointing at this class; NeoForge's own annotation scanning instead
 * looks for `@EmiEntrypoint` - deliberately kept on a separate marker subclass
 * (`neoforge/.../NeoForgeEmiEntrypoint`) rather than this one, since putting it directly here once
 * crashed Fabric's own unrelated entrypoint construction (`java.lang.annotation.AnnotationFormatError:
 * Attempt to create proxy for a non-annotation type: dev.emi.emi.api.EmiEntrypoint`, thrown from
 * Fabric Loader/Kotlin reflection eagerly reading *every* annotation on the class while
 * constructing the `"emi"` entrypoint, confirmed against a real crash log) - a genuine Loom/remap
 * quirk with this specific zero-member annotation type, not something either loader's own EMI
 * actually depends on this class *not* having.
 *
 * Same scope as [net.kernelpanicsoft.boilerplate.compat.rei.BoilerplateREIPlugin]/
 * [net.kernelpanicsoft.boilerplate.compat.jei.BoilerplateJEIPlugin] - only the Crafting Terminal's
 * manual 3x3 grid, the one part of either terminal screen that's both real-vanilla-[Slot]-backed
 * and matches a genuine registered vanilla `CraftingRecipe`. See those classes' KDoc for what's
 * deliberately out of scope.
 */
open class BoilerplateEmiPlugin : EmiPlugin {
	override fun register(registry: EmiRegistry) {
		registry.addRecipeHandler(
			GuiRegistry.CraftingTerminalHook,
			object : StandardRecipeHandler<CraftingTerminalHookMenu> {
				private var lastRequestedTargets: Map<Int, ItemResource>? = null

				override fun getInputSources(handler: CraftingTerminalHookMenu): List<Slot> = handler.outputSlots + handler.gridSlots + handler.inventorySlots
				override fun getCraftingSlots(handler: CraftingTerminalHookMenu): List<Slot> = handler.gridSlots
				override fun supportsRecipe(recipe: EmiRecipe): Boolean = recipe.category == VanillaEmiRecipeCategories.CRAFTING

				/**
				 * `true` either when [StandardRecipeHandler]'s own default
				 * (`context.getInventory().canCraft(recipe)`) already says so (everything's already
				 * sitting in a real slot), or - when it doesn't - every ingredient [recipe] needs shows
				 * up in [CraftingTerminalHookMenu.results], the terminal's own already-synced "what's
				 * reachable" snapshot (see [AbstractTerminalHookMenu.results]'s own KDoc): a pure read
				 * of state the client already has, no request fired, no server round trip. This is
				 * deliberately just *optimistic*, not a guarantee - [results] is a total snapshot, not
				 * a live per-click reservation, so a second player draining the same source between
				 * this check and an actual click can still leave [craft] genuinely short. That's fine:
				 * this only has to get EMI's own fill button to look enabled and let a click reach
				 * [craft] at all (its own `performFill` never calls it otherwise, confirmed against its
				 * real source) - the real, mutating sufficiency check happens there instead. Must never
				 * fire [CraftingTerminalHookMenu.requestIngredientSupply] itself - this is evaluated
				 * every single frame a recipe view is open, not just on a click, so any real side effect
				 * here (pulling stock, animating a ghost item) would fire from the player merely
				 * *looking* at a recipe.
				 */
				override fun canCraft(recipe: EmiRecipe, context: EmiCraftContext<CraftingTerminalHookMenu>): Boolean {
					if (super.canCraft(recipe, context)) return true
					val reachable = context.screenHandler.results.mapTo(HashSet()) { it.resource }
					return targetsOf(recipe).values.all { it in reachable }
				}

				/**
				 * Only delegates to [StandardRecipeHandler]'s own default fill-then-craft logic
				 * ([super.craft]) once every targeted ingredient is already sitting in a real slot
				 * [handler] can see client-side ([availableCount]) - never on [canCraft]'s own
				 * optimistic say-so, which (being a snapshot, not a reservation) isn't something safe
				 * to actually consume against. [super.craft] trusts a prior `true` [canCraft] answer as
				 * vouched-for and proceeds straight to moving/consuming ingredients without re-verifying -
				 * calling it while a targeted cell is still genuinely empty risks it partially consuming
				 * whatever *is* present without ever producing a result, silently destroying those real
				 * ingredients. So a still-missing ingredient here instead only asks the terminal to
				 * supply it ([CraftingTerminalHookMenu.requestIngredientSupply]) and reports failure for
				 * *this* click - a second click succeeds once that supply has actually landed and synced
				 * back to this same slot state.
				 */
				override fun craft(recipe: EmiRecipe, context: EmiCraftContext<CraftingTerminalHookMenu>): Boolean {
					val handler = context.screenHandler
					val targets = targetsOf(recipe)
					val needed = targets.values.groupingBy { it }.eachCount()
					val missing = targets.filterValues { resource -> availableCount(handler, resource) < (needed[resource] ?: 0) }
					if (missing.isNotEmpty()) {
						if (missing != lastRequestedTargets) {
							lastRequestedTargets = missing
							handler.requestIngredientSupply(missing)
						}
						return false
					}
					return super.craft(recipe, context)
				}

				/**
				 * How many of [resource] already sit in a real slot [handler] can see - the client's
				 * own synced state, no server round trip - summed across the same three slot groups
				 * [getInputSources] draws from. Quantity-aware, not just presence: [targetsOf] can map
				 * several grid cells to the same [resource] (a recipe needing more than one plank, say),
				 * and one unit sitting somewhere doesn't make it "available" for every cell that needs
				 * it.
				 */
				private fun availableCount(handler: CraftingTerminalHookMenu, resource: ItemResource): Int =
					(handler.outputSlots + handler.gridSlots + handler.inventorySlots).sumOf { slot ->
						val stack = slot.item
						if (!stack.isEmpty && ItemResource.of(stack) == resource) stack.count else 0
					}
			},
		)

		registry.addExclusionArea(AbstractTerminalHookScreen::class.java) { screen: AbstractTerminalHookScreen<*>, consumer ->
			consumer.accept(Bounds(screen.screenLeft, screen.screenTop, screen.screenWidth, screen.screenHeight))
		}

		registry.addStackProvider(AbstractTerminalHookScreen::class.java) { screen: AbstractTerminalHookScreen<*>, _, _ ->
			screen.hoveredStack?.let { EmiStackInteraction(EmiStack.of(it.itemStack)) } ?: EmiStackInteraction.EMPTY
		}
	}

	/**
	 * One representative [ItemResource] per ingredient of [recipe], keyed by the exact grid cell it
	 * belongs in - [EmiCraftingRecipe]'s own `getInputs()` list is already stored row-major against
	 * a 3-wide grid (confirmed against its own `canFit`/`addWidgets`, which index it via `i % 3`/
	 * `i / 3` directly), so the list index *is* the grid index, no width conversion needed the way
	 * [net.kernelpanicsoft.boilerplate.compat.rei.BoilerplateREIPlugin]'s own REI-side mapping does.
	 */
	private fun targetsOf(recipe: EmiRecipe): Map<Int, ItemResource> =
		recipe.inputs.withIndex().mapNotNull { (index, ingredient) ->
			ingredient.emiStacks.firstOrNull { !it.isEmpty }
				?.itemStack?.takeUnless { it.isEmpty }
				?.let { index to ItemResource.of(it) }
		}.toMap()

}
