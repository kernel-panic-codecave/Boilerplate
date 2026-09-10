package net.kernelpanicsoft.boilerplate.pipe.hook

import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.serialization.ObservableList
import net.kernelpanicsoft.archie.serialization.serializers.ResourceLocationSerializer
import net.kernelpanicsoft.boilerplate.pipe.attachment.AttachmentHolderState
import net.kernelpanicsoft.boilerplate.registry.HookTypeRegistry
import net.minecraft.resources.ResourceLocation

/**
 * Self-contained, per-face persisted state for one attached [PipeHookType] - each concrete hook
 * type ([ExtractionHookState], [SortingHookState]) owns its own subclass with only the fields it
 * actually needs, rather than every hook kind sharing one flat shape. Extends the shared
 * [AttachmentHolderState] (which owns the [type] field) with the hook registry's own lookup.
 */
abstract class HookHolderState(defaultType: ResourceLocation) : AttachmentHolderState(defaultType) {
	val fromRegistry get() = HookTypeRegistry.byId(type)

	/**
	 * Whether this hook currently draws enough pressure to operate - recomputed every tick in
	 * [net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity.tick] by drawing this
	 * hook's own [PipeHookType.basePressureCost] from the segment's reachable
	 * [net.kernelpanicsoft.boilerplate.power.PressureLine], which skips [PipeHookType.tick]
	 * entirely while `false`. This bypasses [net.kernelpanicsoft.boilerplate.power.PressureConsumer.onPressureTick]
	 * entirely rather than implementing that interface - a segment can carry several independent
	 * hooks, each needing its own gate check, not one aggregate multiplier for the whole segment
	 * (see `docs/design/m5-pressure-power.md`'s "Hooks" section). Drives
	 * [net.kernelpanicsoft.boilerplate.pipe.block.BistateHookModelBlock.ACTIVE] via
	 * [PipeHookType.getRenderState], and (for [TerminalHookType]/[CraftingTerminalHookType]
	 * specifically) [net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu.hasPressure]/
	 * [net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu.withdraw]'s own gate.
	 */
	var active: Boolean by field(Boolean.serializer()) { false }

	/**
	 * Whether this hook exposes the far subnet's *own* providers, or just reads what it faces as an
	 * ordinary inventory.
	 *
	 * Lives here rather than on one hook's own state because it belongs to every hook that
	 * [provides][PipeHookType.providesItems] at all: a provider, a sync hook and a pattern provider
	 * are all sources a request may be answered from, and "may this source reach through an interface
	 * into the network behind it" is the same question for each. A hook that provides nothing ignores
	 * this entirely.
	 *
	 * Only meaningful facing an [InterfaceHookType] hook, where the two are genuinely different
	 * things. Off, the interface is a neighbour like a chest: this hook offers whatever is sitting in
	 * its stock and nothing more. On, a request this network cannot answer is handed across the seam
	 * for the far network to answer from its own storage, and what it sends travels both networks in
	 * turn (see [RelayClaim]).
	 *
	 * Off by default. Recursion turns one network's shortfall into another network's work, and a
	 * player who has not asked for that should not get it - a boundary that quietly forwards
	 * everything is no longer a boundary. It stays **one-way** either way: this widens where an
	 * extract may be *sourced* from, never which direction anything moves.
	 */
	var recursive: Boolean by field(Boolean.serializer()) { false }

	/**
	 * Requests this hook has asked the far side of a boundary to fill, still in flight - see
	 * [RelayClaim].
	 *
	 * Only ever non-empty on a [recursive] hook facing an [InterfaceHookType] hook. Persisted,
	 * because a delivery crossing a boundary outlives a chunk unload: the far leg lands in the
	 * interface's stock whether or not this hook was ticking when it did, and a claim forgotten in
	 * between would leave that stock sitting there unclaimed while the original requester asked again.
	 */
	val relays: ObservableList<RelayClaim> by listField(RelayClaim.serializer()) { emptyList() }

	/** Ticks since this hook last tried to carry its [relays] across - see [BoundaryRelay.tick]. */
	var ticksSinceRelay: Int = 0
}
