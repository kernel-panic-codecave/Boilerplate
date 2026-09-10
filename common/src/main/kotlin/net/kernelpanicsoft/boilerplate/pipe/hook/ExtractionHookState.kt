package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.serialization.field
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterModeSerializer
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterContext
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.evaluateGhostSlot
import net.kernelpanicsoft.boilerplate.util.SDyeColor
import net.minecraft.core.BlockPos
import net.minecraft.world.item.DyeColor

/**
 * Self-contained state for one [ExtractionHookType] attachment - what it pulls, how often, how much
 * of it, and how it spreads what it pulls across the destinations that would take it.
 *
 * [intervalTicks] and [amountAuthored] are the two an upgrade system will eventually drive instead
 * of the player setting them directly. They live here rather than only in the config because they
 * are per-hook by nature: one extractor draining a furnace bank and another trickling into a
 * processing line want different answers on the same network.
 */
class ExtractionHookState : HookHolderState(ExtractionHookType.ID) {
	/** Ticks since this hook last attempted an extraction; resets to 0 on every attempt, successful or not. */
	var ticksSinceExtraction: Int = 0

	/**
	 * This face's filter card - what this hook is allowed to pull at all. The same real, single-slot
	 * [FilterCardItem] storage a [SortingHookState] carries, registered as an ordinary vanilla slot
	 * by [net.kernelpanicsoft.boilerplate.pipe.gui.ExtractionHookMenu], so a card here is genuinely
	 * taken from the player's inventory and can be taken back out.
	 */
	val filter: ArchieItemStorage by itemField(1, filter = { it.item is FilterCardItem })

	/** Whether [filter] names what this hook may pull or what it may not - see [accepts]. */
	var filterMode: FilterMode by field(FilterModeSerializer) { FilterMode.WHITELIST }

	/** How this hook spreads its pulls across the destinations that would accept them - see [ExtractionDistribution]. */
	var distribution: ExtractionDistribution by field(ExtractionDistributionSerializer) { ExtractionDistribution.NEAREST_FIRST }

	/**
	 * Ticks between this hook's own pulls - its speed, lower being faster.
	 *
	 * Seeded from the config rather than a literal, so a fresh hook starts at whatever the pack
	 * author set and only diverges once a player actually changes it. An existing hook keeps its own
	 * saved value, which is the point of it being per-hook at all.
	 */
	var intervalTicks: Int by field(Int.serializer()) { BoilerplateConfig.Gameplay.Hooks.extractionIntervalTicks }

	/**
	 * How much one pull moves, in the **authored** unit of whatever it turns out to be pulling -
	 * items, or millibuckets - or [KIND_DEFAULT_AMOUNT] to move one whole batch of that kind.
	 *
	 * Authored for the same reason [FilterHookState.batchSize] is: one extractor pulls items from a
	 * chest and fluids from a tank depending only on what is on the face, so there is no single kind
	 * to read a platform count as. The conversion happens per resource, in [ExtractionHookType].
	 */
	var amountAuthored: Long by field(Long.serializer()) { KIND_DEFAULT_AMOUNT }

	/**
	 * Whether this hook may pull [resource] at all.
	 *
	 * An empty card lets **everything** through, under either mode - deliberately unlike
	 * [SortingHookState.accepts], where an empty whitelist is a meaningful "deny everything". A
	 * sorting hook with no card is a destination nobody has configured yet; an extraction hook with
	 * no card is the ordinary case, and every extractor placed before this setting existed has one.
	 * Reading an empty card as deny-all would silently stop all of them.
	 */
	fun accepts(resource: ResourceComponent): Boolean {
		val card = filter.get(0).resource
		if (card.isBlank) return true

		val matches = evaluateGhostSlot(card, FilterContext(resource, color))
		return when (filterMode) {
			FilterMode.WHITELIST -> matches
			FilterMode.BLACKLIST -> !matches
		}
	}

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

	companion object {
		/** [amountAuthored]'s "whatever this kind moves at a time" value - see [net.kernelpanicsoft.boilerplate.pipe.network.ResourceNetworkType.extractionBatch]. */
		const val KIND_DEFAULT_AMOUNT = 0L

		/** The slowest this hook may be set to run, in ticks between pulls - matches the config slider's own range. */
		const val MAX_INTERVAL_TICKS = 200
	}
}

/**
 * Deliberately a plain data class, not a `@JvmInline value class`: a value class's generated
 * serializer encodes its single property transparently (no structural wrapper), which hits the
 * same rootless-null limitation this type exists to work around.
 */
@Serializable
private data class ColorSlot(val color: SDyeColor? = null)
