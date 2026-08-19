package net.kernelpanicsoft.tubularstorage.warehouse.rack

import net.minecraft.network.chat.Component

/** A rack block entity's own summary of what it's holding, shown to a player via [RackBlock.useWithoutItem] - racks are otherwise driven entirely by the gantry/pipes, not a per-rack GUI (see the warehouse terminal, `docs/design/m3-warehouse-storage.md`'s player-facing search/withdraw surface). */
interface RackBlockEntity {
	fun describeContents(): Component
}
