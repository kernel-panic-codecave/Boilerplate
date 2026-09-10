package net.kernelpanicsoft.boilerplate.crafting

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.kernelpanicsoft.boilerplate.resource.ResourceIdentity
import net.kernelpanicsoft.boilerplate.resource.SResourceComponent
import net.kernelpanicsoft.boilerplate.resource.SResourceStack

/**
 * Whether a [Pattern] was encoded from a matching vanilla [net.minecraft.world.item.crafting.CraftingRecipe]
 * (positional - [Pattern.inputs] mirrors the 3x3 grid exactly as it was, blank cells included) or
 * manually authored ([Pattern.inputs] is just an unordered bag of what the grid held, `PROCESSING`
 * recipes having no vanilla crafting-table analogue) - see `docs/design/m4-crafting-automation.md`.
 */
@Serializable(with = PatternKindSerializer::class)
enum class PatternKind { CRAFTING, PROCESSING }

/**
 * Encodes [PatternKind] by name via `encodeString`/`decodeString`, not kotlinx.serialization's
 * default `encodeEnum` - knbt's `AbstractNbtEncoder` never overrides that, so it crashes the
 * instant a non-default enum constant (`PROCESSING` here) actually gets persisted. The same latent
 * gap `FilterModeSerializer`/`DirectionSerializer`/`DyeColorSerializer` already work around - see
 * `docs/design/m2-sorting-routing.md`.
 */
object PatternKindSerializer : KSerializer<PatternKind> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PatternKind", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: PatternKind) = encoder.encodeString(value.name)
	override fun deserialize(decoder: Decoder): PatternKind = PatternKind.valueOf(decoder.decodeString())
}

/**
 * One resolved pattern: [inputs] (up to 9, one per grid cell for [PatternKind.CRAFTING], an
 * unordered bag for [PatternKind.PROCESSING]) consumed to produce [outputs]. Snapshotted at encode
 * time rather than referencing a live [net.minecraft.world.item.crafting.RecipeHolder] - see
 * [PatternEncoder] - so a later game/recipe change never retroactively invalidates an
 * already-encoded pattern, the same tradeoff AE2's own pattern encoding makes.
 *
 * Both sides are [SResourceComponent], not items: a `PROCESSING` pattern's slots may hold **any**
 * registered [net.kernelpanicsoft.boilerplate.resource.ResourceKind] - a fluid, an addon's gas -
 * on either side, so `1000mB water + 1 clay -> 1 slurry` is one pattern rather than something the
 * crafting layer cannot express at all. A `CRAFTING` pattern is still item-only in practice, since
 * it has to match a real vanilla recipe ([PatternEncoder] enforces that at encode time), but it
 * carries the same generic type rather than a second parallel shape.
 *
 * A resource's own required quantity is carried per entry ([ResourceStack.amount]) rather than by
 * how many of the 9 cells hold it: fluids have no "one per cell" reading at all, and an item
 * pattern that wants 64 of something should be one entry of 64, not 64 entries. [requiredInputs]
 * is what collapses the cells into per-resource totals.
 */
@Serializable
data class Pattern(
	val inputs: List<SResourceStack<SResourceComponent>>,
	val outputs: List<SResourceStack<SResourceComponent>>,
	val kind: PatternKind,
) {
	/**
	 * [inputs] collapsed into one total per distinct, non-blank resource - what a step's own
	 * execution actually checks "do I have enough to run this" against.
	 *
	 * Keyed by [ResourceIdentity] rather than by the resource itself because a pattern may now hold
	 * fluids, and `FluidResource` (Common Storage Lib 0.0.5) overrides neither `equals` nor
	 * `hashCode` - grouping raw fluid resources would put every cell in its own bucket and report
	 * `1000mB water` twice instead of `2000mB` once.
	 */
	fun requiredInputs(): Map<ResourceIdentity, Long> =
		inputs.filter { !it.resource.isBlank }
			.groupBy { ResourceIdentity.of(it.resource) }
			.mapValues { (_, entries) -> entries.sumOf { it.amount } }

	/** How much of [resource] one run of this pattern produces, or `null` if it produces none at all - identity-compared, so it answers correctly for a fluid output too. */
	fun outputAmount(resource: ResourceComponent): Long? {
		val key = ResourceIdentity.of(resource)
		return outputs.firstOrNull { ResourceIdentity.of(it.resource) == key }?.amount
	}

	/** Whether this pattern produces [resource] at all - see [outputAmount]. */
	fun produces(resource: ResourceComponent): Boolean = outputAmount(resource) != null

	/**
	 * Value equality over the cells, compared through [ResourceIdentity] rather than through
	 * [ResourceStack]'s own.
	 *
	 * The generated `data class` equality is wrong for a pattern holding a fluid, and silently so.
	 * `ResourceStack` is a record, so its `equals` delegates to the resource's - and `FluidResource`
	 * (Common Storage Lib 0.0.5) defines none, leaving reference identity. Two patterns with
	 * identical contents are therefore unequal the moment one of them has been through NBT, which is
	 * *every* stored pattern: a [PatternItem] is written and read back, minting fresh resources each
	 * time.
	 *
	 * That matters because equality is how a pattern gets **found**, not merely compared -
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState.heldPatterns]`.contains(...)`
	 * and `indexOfPattern(...)` are what resolve a job step to the machine that runs it. Without
	 * this, a fluid step sits forever reporting "No free pattern provider" while the pattern is
	 * plainly sitting in one.
	 *
	 * Order-sensitive, exactly as the generated equality was: a `CRAFTING` pattern's grid is
	 * positional, and a `PROCESSING` pattern's cells are encoded in a deterministic order.
	 */
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is Pattern) return false
		return kind == other.kind && sameCells(inputs, other.inputs) && sameCells(outputs, other.outputs)
	}

	override fun hashCode(): Int {
		var result = kind.hashCode()
		result = 31 * result + cellsHash(inputs)
		result = 31 * result + cellsHash(outputs)
		return result
	}

	companion object {
		val EMPTY = Pattern(emptyList(), emptyList(), PatternKind.CRAFTING)
		const val GRID_SIZE = 9

		/** Whether two cell lists hold the same resources, in the same order, at the same amounts - see [Pattern.equals]. */
		private fun sameCells(a: List<ResourceStack<ResourceComponent>>, b: List<ResourceStack<ResourceComponent>>): Boolean {
			if (a.size != b.size) return false
			for (i in a.indices) {
				if (a[i].amount != b[i].amount) return false
				if (ResourceIdentity.of(a[i].resource) != ResourceIdentity.of(b[i].resource)) return false
			}
			return true
		}

		/** [sameCells]' own hash, so equal patterns hash equal. */
		private fun cellsHash(cells: List<ResourceStack<ResourceComponent>>): Int {
			var result = 1
			for (cell in cells) {
				result = 31 * result + ResourceIdentity.of(cell.resource).hashCode()
				result = 31 * result + cell.amount.hashCode()
			}
			return result
		}
	}
}

/** [Pattern.outputs], scaled by however many times the pattern actually ran - a convenience for callers that only care about the resulting stacks, not the pattern itself. */
fun Pattern.outputsFor(runs: Long): List<ResourceStack<ResourceComponent>> =
	outputs.map { ResourceStack(it.resource, it.amount * runs) }
