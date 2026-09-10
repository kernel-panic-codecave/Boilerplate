package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import androidx.compose.runtime.*
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.gui.composables.basic.Label
import net.kernelpanicsoft.archie.gui.composables.input.Checkbox
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.gui.ClickHandler
import net.kernelpanicsoft.boilerplate.pipe.gui.ResourceGhostSlotGrid
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.resource.ResourceComponentSerializer
import net.kernelpanicsoft.boilerplate.resource.ResourceKind
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * Matches any one of the resources named in [ResourceConditionState.resourceMatches] - whatever kind
 * each of them is.
 *
 * The one card that names resources outright, in place of the three kind-specific ones there used to
 * be. Those were the same card three times over: an item grid, a fluid grid and a chemical grid,
 * each refusing what the others took, each needing its own item, texture, recipe and registration -
 * and none of them able to express "this item **or** that fluid" in a single card, which a filter on
 * a pipe carrying both kinds at once genuinely wants. Worse, the pattern did not scale: a kind an
 * addon registered had no card until someone wrote a fourth one.
 *
 * A grid of resources is the honest shape, because [ResourceComponentSerializer] already stores a
 * resource of any kind and [ResourceGhostSlotGrid] already draws one. Matching is per kind too: the
 * entry and the tested resource must be of the same kind, and their identities are compared through
 * that kind's own [ResourceKind.identityOf] / [ResourceKind.baseIdentityOf].
 *
 * Comparison never goes through `==`: `FluidResource` overrides neither `equals` nor `hashCode`
 * (Common Storage Lib 0.0.5), so a direct comparison against a freshly deserialised resource never
 * matches and the card would silently reject everything.
 */
object ResourceConditionType : FilterConditionType<ResourceConditionState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "resource"

	override fun createState(): ResourceConditionState = ResourceConditionState()

	override fun matches(state: ResourceConditionState, context: FilterContext): Boolean {
		val tested = context.resource
		val kind = ResourceKindRegistry.forResource(tested) ?: return false
		val key = identity(kind, tested, state.matchComponents)

		return state.resourceMatches.any { entry ->
			if (entry.isBlank) return@any false
			// Same kind first: an item entry must never match a fluid that happens to share a
			// registry path, and a kind's identity is only comparable against its own.
			val entryKind = ResourceKindRegistry.forResource(entry) ?: return@any false
			if (entryKind !== kind) return@any false
			identity(entryKind, entry, state.matchComponents) == key
		}
	}

	private fun identity(kind: ResourceKind, resource: ResourceComponent, matchComponents: Boolean): Any =
		if (matchComponents) kind.identityOf(resource) else kind.baseIdentityOf(resource)

	@Composable
	override fun content(editor: FilterCardEditor, state: ResourceConditionState, clickHandler: ClickHandler) {
		var matches by remember { mutableStateOf(state.resourceMatches.toList()) }
		var matchComponents by remember { mutableStateOf(state.matchComponents) }

		fun set(index: Int, resource: ResourceComponent) {
			matches = matches.toMutableList().also { it[index] = resource }
			state.resourceMatches[index] = resource
			editor.push(state::resourceMatches, matches, ListSerializer(ResourceComponentSerializer))
		}

		Row(horizontalArrangement = Arrangement.spacedBy(4), verticalAlignment = Alignment.CenterVertically) {
			Checkbox(
				checked = matchComponents,
				onCheckedChange = {
					matchComponents = it
					state.matchComponents = it
					editor.push(state::matchComponents, it, Boolean.serializer())
				},
			)
			Label(Component.literal("Match components"))
		}

		Label(Component.literal("Resources"))
		// Anything the ghost slot can name is allowed in, unlike the kind-specific cards this
		// replaces - the slot already reads whichever kind claims the carried stack, a bucket naming
		// its fluid and anything else naming itself, and every one of those is now matchable.
		ResourceGhostSlotGrid(
			resources = matches,
			columns = 3,
			carried = { editor.carried() },
			onPlace = { index, resource -> set(index, resource) },
			onClear = { index -> set(index, ItemResource.BLANK) },
			clickHandler = clickHandler,
			handleClick = { null },
		)
	}
}
