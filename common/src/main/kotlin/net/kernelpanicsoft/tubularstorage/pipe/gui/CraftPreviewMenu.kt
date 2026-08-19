package net.kernelpanicsoft.tubularstorage.pipe.gui

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.tubularstorage.network.SItemResource

/**
 * Shared shape [CraftQuantityDialog] needs from whichever terminal-flavored menu opened it -
 * implemented by both [TerminalHookMenu] and [CraftingTerminalHookMenu], which otherwise don't
 * share a common menu superclass (see [CraftingTerminalHookMenu]'s own KDoc for why).
 */
interface CraftPreviewMenu {
	/** The most recently received craft-preview result - `resource to maxCraftable`. */
	val craftPreview: Pair<SItemResource, Long>?

	/** Asks how much of [resource] is currently craftable, up to [upperBound] - a dry run, nothing is requested. */
	fun requestCraftPreview(resource: ItemResource, upperBound: Long)
}
