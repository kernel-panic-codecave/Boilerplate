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
				override fun getInputSources(handler: CraftingTerminalHookMenu): List<Slot> = handler.outputSlots + handler.gridSlots + handler.inventorySlots
				override fun getCraftingSlots(handler: CraftingTerminalHookMenu): List<Slot> = handler.gridSlots
				override fun supportsRecipe(recipe: EmiRecipe): Boolean = recipe.category == VanillaEmiRecipeCategories.CRAFTING

				/**
				 * Asks the terminal to supply anything [recipe] needs that it can reach (storage, its
				 * own inbox - see [CraftingTerminalHookMenu.requestIngredientSupply]) before running
				 * the default fill unchanged, which now also draws from [handler]'s own `outputSlots`
				 * (the inbox) alongside the grid and player inventory. That request isn't instant for
				 * a network-sourced ingredient, so the *first* click still reports missing ingredients
				 * the same as any real shortfall - a second click succeeds once it's arrived.
				 */
				override fun craft(recipe: EmiRecipe, context: EmiCraftContext<CraftingTerminalHookMenu>): Boolean {
					val targets = targetsOf(recipe)
					if (targets.isNotEmpty()) context.screenHandler.requestIngredientSupply(targets)
					return super.craft(recipe, context)
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
