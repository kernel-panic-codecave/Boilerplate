package net.kernelpanicsoft.tubularstorage.crafting

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.kernelpanicsoft.tubularstorage.network.SItemResource
import net.kernelpanicsoft.tubularstorage.network.SResourceStack

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
 * unordered bag for [PatternKind.PROCESSING] - a resource's own required quantity is simply how
 * many of the 9 entries hold it, the same "count via slot occupancy" shape a real crafting grid
 * already has) consumed to produce [outputs]. Snapshotted at encode time rather than referencing a
 * live [net.minecraft.world.item.crafting.RecipeHolder] - see [PatternEncoder] - so a later game/
 * recipe change never retroactively invalidates an already-encoded pattern, the same tradeoff
 * AE2's own pattern encoding makes.
 */
@Serializable
data class Pattern(
	val inputs: List<SItemResource>,
	val outputs: List<SResourceStack<SItemResource>>,
	val kind: PatternKind,
) {
	/** [inputs] collapsed into one count per distinct, non-blank resource - what [AssemblyTableBlockEntity] actually needs to check "do I have enough to run this" against. */
	fun requiredInputs(): Map<ItemResource, Long> {
		val required = LinkedHashMap<ItemResource, Long>()
		for (resource in inputs) {
			if (resource.isBlank) continue
			required[resource] = (required[resource] ?: 0L) + 1L
		}
		return required
	}

	companion object {
		const val GRID_SIZE = 9
	}
}

/** [Pattern.outputs], scaled by however many times the pattern actually ran - a convenience for callers that only care about the resulting stacks, not the pattern itself. */
fun Pattern.outputsFor(runs: Long): List<ResourceStack<ItemResource>> =
	outputs.map { ResourceStack(it.resource, it.amount * runs) }
