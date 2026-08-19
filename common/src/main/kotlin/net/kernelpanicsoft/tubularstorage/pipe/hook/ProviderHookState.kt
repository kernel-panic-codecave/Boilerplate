package net.kernelpanicsoft.tubularstorage.pipe.hook

/**
 * Self-contained state for one [ProviderHookType] attachment - a [SortingHookState] like
 * [FilterHookType]/[SyncHookType], so a provider can filter what it exposes the same way a sync
 * hook already does (`docs/design/m2-sorting-routing.md`'s subnet boundary section: a provider
 * facing an interface hook only exposes matching items across the seam). [SortingHookState.routing]'s
 * `priority`/`color` go unused here - [net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment]
 * only ever reads [SortingHookState.accepts] - but reusing the whole type (and
 * [net.kernelpanicsoft.tubularstorage.pipe.gui.SortingHookMenu]/[net.kernelpanicsoft.tubularstorage.pipe.gui.SortingHookScreen])
 * outright is simpler than a filter-only variant with its own GUI.
 */
class ProviderHookState : SortingHookState(ProviderHookType.ID)
