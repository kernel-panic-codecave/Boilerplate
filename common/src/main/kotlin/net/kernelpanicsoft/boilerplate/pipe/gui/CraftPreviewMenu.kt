package net.kernelpanicsoft.boilerplate.pipe.gui

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.boilerplate.network.SItemResource
import net.kernelpanicsoft.boilerplate.network.SResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceComponent

/**
 * Shared shape [CraftQuantityDialog] needs from whichever terminal-flavored menu opened it -
 * implemented by both [AbstractTerminalHookMenu] and [CraftingTerminalHookMenu], which otherwise don't
 * share a common menu superclass (see [CraftingTerminalHookMenu]'s own KDoc for why).
 */
interface CraftPreviewMenu {
	/** The most recently received craft-preview result - `resource to maxCraftable`. */
	val craftPreview: Pair<SResourceComponent, Long>?

	/** Asks how much of [resource] is currently craftable, up to [upperBound] - a dry run, nothing is requested. */
	fun requestCraftPreview(resource: ResourceComponent, upperBound: Long)
}
