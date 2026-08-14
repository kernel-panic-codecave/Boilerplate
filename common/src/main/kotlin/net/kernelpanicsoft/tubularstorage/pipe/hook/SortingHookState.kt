package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule

/** Self-contained state for one [SortingHookType] attachment: its filter grid and routing config. */
class SortingHookState : HookHolderState(SortingHookType.ID) {
	var routing: RoutingModule by field(RoutingModule.serializer()) { RoutingModule() }

	/** This face's 3x3 filter grid, matched against [routing]'s mode - see `docs/design/m2-sorting-routing.md`. */
	val filter by itemField(9)
}
