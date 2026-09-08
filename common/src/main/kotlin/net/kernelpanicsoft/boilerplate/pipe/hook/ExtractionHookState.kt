package net.kernelpanicsoft.boilerplate.pipe.hook

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.field
import net.kernelpanicsoft.boilerplate.util.SDyeColor
import net.minecraft.core.BlockPos
import net.minecraft.world.item.DyeColor

/** Self-contained state for one [ExtractionHookType] attachment. */
class ExtractionHookState : HookHolderState(ExtractionHookType.ID) {
	/** Ticks since this hook last attempted an extraction; resets to 0 on every attempt, successful or not. */
	var ticksSinceExtraction: Int = 0

	/**
	 * Destinations already served since this cycle began, skipped while choosing the next one.
	 *
	 * Without it a source keeps refilling whichever destination the route cache first settled on -
	 * the cache is keyed on network, resource and colour, so the same answer comes back until the
	 * topology changes, and one machine of several receives everything. Excluding what has already
	 * been served makes the search move on; the set is cleared once nothing new accepts, which
	 * starts the round again.
	 *
	 * Deliberately not persisted. It is a fairness cursor, not state anyone can observe, and a
	 * reload simply starts the cycle from the top.
	 */
	val servedThisCycle: MutableSet<BlockPos> = mutableSetOf()

	/**
	 * The consignment color (if any) this hook stamps on whatever it sends out - see
	 * `docs/design/m2-sorting-routing.md`. Backed by [ColorSlot], not a bare nullable
	 * [net.kernelpanicsoft.archie.serialization.NBTHolder.field] - a `field<T?>` encodes rootless
	 * (outside any structure), and knbt can't represent a bare `null` there (only inside a
	 * `@Serializable` class's own structured encoding, which is why
	 * [net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule.color] - nested inside that
	 * data class - works fine).
	 */
	private var colorSlot: ColorSlot by field { ColorSlot() }
	var color: DyeColor?
		get() = colorSlot.color
		set(value) {
			colorSlot = ColorSlot(value)
		}
}

/**
 * Deliberately a plain data class, not a `@JvmInline value class`: a value class's generated
 * serializer encodes its single property transparently (no structural wrapper), which hits the
 * same rootless-null limitation this type exists to work around.
 */
@Serializable
private data class ColorSlot(val color: SDyeColor? = null)
