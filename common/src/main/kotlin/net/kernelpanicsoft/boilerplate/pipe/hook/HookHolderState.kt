package net.kernelpanicsoft.boilerplate.pipe.hook

import kotlinx.serialization.builtins.serializer
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
}
