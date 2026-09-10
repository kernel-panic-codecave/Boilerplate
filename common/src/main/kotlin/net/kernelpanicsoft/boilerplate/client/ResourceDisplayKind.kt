package net.kernelpanicsoft.boilerplate.client

import androidx.compose.runtime.Composable
import dev.architectury.platform.Platform
import dev.engine_room.flywheel.api.model.Mesh
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.archie.util.buildComponent
import net.kernelpanicsoft.archie.util.requireMinecraftClient
import net.kernelpanicsoft.boilerplate.resource.displayName
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.world.item.ItemStack

/**
 * How a [net.kernelpanicsoft.boilerplate.resource.ResourceKind] is **drawn** - in a slot, moving
 * through a pipe, and riding the warehouse crane.
 *
 * The rendering counterpart of
 * [net.kernelpanicsoft.boilerplate.resource.ResourceStorageKind], and the other half of what makes
 * "register a kind and it works" true: without one, an addon's resource is stored, routed, filtered
 * and crafted correctly and then shows up as an empty slot and invisible cargo, because every
 * surface that draws a resource would have had to name it.
 *
 * There are exactly two surfaces: a slot face in a GUI ([SlotFace]) and a baked mesh in the world
 * ([worldMesh]). Everything Boilerplate draws goes through Flywheel, so the world half is geometry
 * handed to an instancer rather than a draw call issued per frame.
 *
 * Reached only through [net.kernelpanicsoft.boilerplate.resource.ResourceKind.display], which is
 * `null` by default - a kind that provides none is simply not drawn, exactly as an unrecognised
 * resource was not drawn before. **Client-only**: nothing on a dedicated server reads that
 * property, so the implementation class is never loaded there.
 *
 * Every method has a do-nothing default, so a kind may implement as much of this as it has art for
 * - a slot face and nothing else is a perfectly good display kind.
 */
interface ResourceDisplayKind {
	/**
	 * The 18x18 face [resource] draws inside a slot - the item's own stack rendering, a fluid's
	 * still sprite, whatever an addon's kind looks like.
	 *
	 * Draws the slot background too, not just the contents: a kind's face is the whole cell, which
	 * is what lets a fluid's sprite fill the frame edge to edge while an item sits inset inside it.
	 * [countText] is the corner label the caller wants shown, already formatted; a kind whose face
	 * draws its own amount (see
	 * [net.kernelpanicsoft.boilerplate.resource.ResourceKind.drawsOwnAmount]) will be handed `null`.
	 * [enabled] is the surrounding control's own state - a terminal greys its whole grid out when
	 * the network has no pressure, and a face that ignores it simply stays lit.
	 *
	 * Every parameter is required, [enabled] included, even though `true` would be a fine default.
	 * A `@Composable` with default arguments compiles to *two* abstract methods - the real one and a
	 * default-mask variant - and an override compiled in a different module than this interface
	 * binds to the wrong one of the pair, producing an `AbstractMethodError` the moment the face is
	 * drawn. An addon registering a kind necessarily implements this from its own module, so the
	 * default is not worth the trap.
	 */
	@Composable
	fun SlotFace(resource: ResourceComponent, amount: Long, isHovered: Boolean, countText: String?, enabled: Boolean) {
	}

	/**
	 * The baked [Mesh] this kind's [resource] is drawn as in the world - tumbling down a pipe,
	 * riding the warehouse crane - or `null` for a kind with no world visual.
	 *
	 * A mesh rather than a draw call because everything is rendered through Flywheel, which
	 * instances baked geometry: the mesh is built once per distinct resource and every copy of it on
	 * screen shares it, however many are in flight. Callers position, scale and spin it through the
	 * instance transform, so a mesh is expected to be centred about its own origin at natural size.
	 *
	 * Implementations should cache - [net.kernelpanicsoft.boilerplate.client.InstancedMeshes] does
	 * for both kinds Boilerplate ships, and is the obvious thing for an addon kind to build on.
	 */
	fun worldMesh(resource: ResourceComponent, amount: Long): Mesh? = null

	/**
	 * Every line a tooltip for [amount] of [resource] should show, name first.
	 *
	 * A list rather than one component because an item's tooltip is not one line: vanilla builds
	 * enchantments, durability, lore, the advanced-tooltip registry id and whatever other mods have
	 * attached, and drawing only the name throws all of it away. The item kind hands vanilla's own
	 * lines straight back; a measured kind builds its own, since nothing in vanilla knows how to
	 * describe a bucket-and-a-half of something.
	 *
	 * Defaults to the name alone, which is what a kind with nothing further to say would produce
	 * anyway.
	 */
	fun tooltipLines(resource: ResourceComponent, amount: Long): List<Component> = listOf(resource.displayName())

	/**
	 * How [worldMesh] moves while it travels - see [WorldMeshMotion].
	 *
	 * Defaults to [WorldMeshMotion.SPIN], which is what an item does and what anything with a
	 * definite upright wants.
	 */
	val worldMeshMotion: WorldMeshMotion get() = WorldMeshMotion.SPIN

	/**
	 * This kind's resource held *inside* [stack], or `null` - a bucket of water read as the water
	 * resource, so dropping one into a ghost slot sets the fluid rather than the bucket.
	 *
	 * How a ghost slot accepts a kind that has no item form of its own: the player is always
	 * carrying an [ItemStack], so every non-item kind needs a way to say what one of its resources
	 * looks like when carried.
	 */
	fun carriedIn(stack: ItemStack): ResourceComponent? = null
}

/**
 * How a resource's [ResourceDisplayKind.worldMesh] moves while it is being carried - down a pipe, or
 * on the warehouse crane.
 *
 * A property of the *kind* rather than of the site drawing it, because it is a fact about the
 * resource: a crate of ingots has an upright and a droplet of water does not.
 */
enum class WorldMeshMotion {
	/** Turns about its vertical axis, the way a dropped item entity does - it has an upright, and keeps it. */
	SPIN,

	/**
	 * Tumbles freely on every axis and sloshes as it goes - a droplet of something liquid, which has
	 * no upright to keep.
	 *
	 * The slosh is a squash and stretch out of phase per axis, not the per-vertex ripple the
	 * immediate-mode droplet had. Instanced geometry is uploaded once and only its *transform*
	 * changes per frame, so a rippling surface would mean a fresh mesh per frame - and Flywheel keys
	 * an instancer to a model, so that is an instancer built and thrown away every frame per
	 * droplet, which is the whole thing instancing buys away. This is the part of the effect that
	 * survives being instanced, and it costs nothing.
	 */
	TUMBLE,
}

/**
 * The lines a **measured** kind's tooltip shows - one whose amounts are a quantity of a substance
 * rather than a count of objects, and which therefore has a unit to name.
 *
 * Shared by fluids and chemicals, and by anything an addon registers in the same shape: the name,
 * the amount in the kind's own authored unit, and the mod it came from - which vanilla adds for an
 * item and nothing adds for anything else.
 */
fun resourceTooltip(resource: ResourceComponent, additionalLines: List<Component> = emptyList()): List<Component> = buildList {
	val kind = ResourceKindRegistry.forResource(resource)!!
	add(resource.displayName())
	addAll(additionalLines)
	if (requireMinecraftClient.options.advancedItemTooltips) {
		add(buildComponent {
			style { color = TextColor.fromLegacyFormat(ChatFormatting.DARK_GRAY) }
			text(kind.registryId(resource).toString())
		})
	}
	kind.registryId(resource)?.namespace?.let { namespace ->
		val name = Platform.getOptionalMod(namespace).map { it.name }.orElse(namespace)!!
		add(buildComponent {
			style {
				color = TextColor.fromLegacyFormat(ChatFormatting.BLUE)
				italic = true
			}
			text(name)
		})
	}
}

fun resourceTooltip(resource: ResourceComponent, builder: MutableList<Component>.() -> Unit): List<Component> = resourceTooltip(resource, buildList(builder))
