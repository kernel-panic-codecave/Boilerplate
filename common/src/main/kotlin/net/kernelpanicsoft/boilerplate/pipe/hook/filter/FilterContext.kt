package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.minecraft.world.item.DyeColor

/**
 * Everything a [FilterCardState] condition might need to decide whether it matches - the resource
 * being tested, plus [color] (a traveling item's own consignment color, only ever non-null on
 * [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter]'s push-routing side; a standing
 * request pulled via [net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment] has no
 * such color, so a [FilterCardType.COLOR] card never matches there).
 */
data class FilterContext(val resource: ItemResource, val color: DyeColor? = null)
